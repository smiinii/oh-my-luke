package io.ohmyluke.ai.codex;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.ohmyluke.ai.AiFailureCode;
import io.ohmyluke.ai.AiRequest;
import io.ohmyluke.ai.AiRuntime;
import io.ohmyluke.ai.AiRuntimeResult;
import io.ohmyluke.ai.AiRuntimeStatus;
import io.ohmyluke.ai.AiTokenUsage;
import io.ohmyluke.tool.ProcessWorkspace;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Official Codex CLI adapter that reuses the user's saved login without reading credentials. */
public final class CodexCliRuntime implements AiRuntime {
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(5);
    private static final int PROBE_OUTPUT_LIMIT = 4096;
    private static final String SANDBOX_MODE = "read-only";

    private final CodexCliConfiguration configuration;
    private final String fingerprint;
    private final CodexProcessRunner processes = new CodexProcessRunner();
    private final CodexCliJsonParser parser = new CodexCliJsonParser();
    private final CodexInvocationStore store;
    private final ObjectMapper mapper = new ObjectMapper();

    public CodexCliRuntime(CodexCliConfiguration configuration) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.fingerprint = "codex-cli:v1:sha256:" + CodexHashing.configuration(configuration);
        this.store = new CodexInvocationStore(
                configuration.projectRoot(),
                configuration.maxOutputBytes());
    }

    @Override
    public String fingerprint() {
        return fingerprint;
    }

    public CodexRuntimeProbe probe() {
        CodexProcessResult version = processes.run(
                List.of(configuration.executable(), "--version"),
                configuration.projectRoot(),
                new byte[0],
                PROBE_TIMEOUT,
                PROBE_OUTPUT_LIMIT);
        if (!version.started() || version.timedOut() || version.exitCode() != 0) {
            return CodexRuntimeProbe.unavailable();
        }
        CodexProcessResult login = processes.run(
                List.of(configuration.executable(), "login", "status"),
                configuration.projectRoot(),
                new byte[0],
                PROBE_TIMEOUT,
                PROBE_OUTPUT_LIMIT);
        boolean authenticated = login.started()
                && !login.timedOut()
                && login.exitCode() == 0
                && !login.outputLimitExceeded();
        return new CodexRuntimeProbe(true, authenticated, safeVersion(version.stdout()));
    }

    @Override
    public AiRuntimeResult invoke(AiRequest request) {
        Objects.requireNonNull(request, "request");
        byte[] prompt;
        try {
            prompt = encodePrompt(request);
        } catch (JsonProcessingException error) {
            return AiRuntimeResult.failure(AiFailureCode.INVALID_RESPONSE, 0);
        }
        if (prompt.length > configuration.maxInputBytes()) {
            return AiRuntimeResult.failure(AiFailureCode.INPUT_LIMIT_EXCEEDED, 0);
        }

        String requestFingerprint = CodexHashing.request(request);
        try (CodexInvocationStore.LockedInvocation invocation = store.lock(request.invocationId())) {
            var stored = invocation.load();
            if (stored.isPresent()) {
                CodexStoredInvocation completed = stored.orElseThrow();
                if (!completed.requestFingerprint().equals(requestFingerprint)
                        || !completed.runtimeFingerprint().equals(fingerprint)) {
                    return AiRuntimeResult.failure(AiFailureCode.REQUEST_CONFLICT, 0);
                }
                return completed.result();
            }

            AiRuntimeResult result = execute(request, prompt);
            if (shouldStore(result)) {
                invocation.save(CodexStoredInvocation.current(
                        requestFingerprint,
                        fingerprint,
                        result));
            }
            return result;
        } catch (CodexInvocationStore.CodexStoreException error) {
            return AiRuntimeResult.failure(AiFailureCode.INVALID_RESPONSE, 0);
        }
    }

    private AiRuntimeResult execute(AiRequest request, byte[] prompt) {
        String operationId = CodexHashing.safeFileId(request.invocationId()).substring(0, 24);
        try (ProcessWorkspace workspace = ProcessWorkspace.create(
                configuration.projectRoot(),
                "codex",
                operationId)) {
            CodexProcessResult process = processes.run(
                    command(workspace.projectRoot()),
                    workspace.projectRoot(),
                    prompt,
                    configuration.timeout(),
                    configuration.maxOutputBytes());
            return classify(process);
        } catch (RuntimeException error) {
            return AiRuntimeResult.failure(AiFailureCode.EXECUTION_FAILED, 0);
        }
    }

    private AiRuntimeResult classify(CodexProcessResult process) {
        if (!process.started()) {
            return AiRuntimeResult.failure(AiFailureCode.RUNTIME_UNAVAILABLE, 0);
        }
        if (process.timedOut()) {
            return AiRuntimeResult.failure(AiFailureCode.TIMED_OUT, 0);
        }
        if (process.outputLimitExceeded()) {
            return AiRuntimeResult.failure(AiFailureCode.OUTPUT_LIMIT_EXCEEDED, 0);
        }

        CodexParsedOutput parsed = null;
        try {
            if (!process.stdout().isBlank()) {
                parsed = parser.parse(process.stdout());
            }
        } catch (IllegalArgumentException ignored) {
            // A stable INVALID_RESPONSE below replaces the raw parser/provider error.
        }

        if (process.exitCode() != 0) {
            AiFailureCode code = classifyFailure(process.stderr());
            return failureWithObservedUsage(code, parsed);
        }
        if (process.inputWriteFailed()) {
            return AiRuntimeResult.failure(AiFailureCode.EXECUTION_FAILED, 0);
        }
        if (parsed == null
                || parsed.failed()
                || !parsed.completed()
                || parsed.finalMessage().isBlank()) {
            return failureWithObservedUsage(AiFailureCode.INVALID_RESPONSE, parsed);
        }
        if (parsed.tokenUsage().available()) {
            return AiRuntimeResult.success(
                    parsed.finalMessage(),
                    parsed.tokenUsage(),
                    parsed.threadId());
        }
        return new AiRuntimeResult(
                AiRuntimeStatus.SUCCESS,
                parsed.finalMessage(),
                null,
                0,
                AiTokenUsage.unavailable(),
                parsed.threadId());
    }

    private static AiRuntimeResult failureWithObservedUsage(
            AiFailureCode code,
            CodexParsedOutput parsed) {
        if (parsed != null && parsed.tokenUsage().available()) {
            return AiRuntimeResult.failure(code, parsed.tokenUsage(), parsed.threadId());
        }
        return AiRuntimeResult.failure(code, 0);
    }

    private List<String> command(java.nio.file.Path workspaceRoot) {
        ArrayList<String> command = new ArrayList<>();
        command.add(configuration.executable());
        command.add("exec");
        command.add("--json");
        command.add("--color");
        command.add("never");
        command.add("--ephemeral");
        command.add("--sandbox");
        command.add(SANDBOX_MODE);
        command.add("--skip-git-repo-check");
        command.add("--cd");
        command.add(workspaceRoot.toString());
        configuration.modelSelection().explicitModel().ifPresent(model -> {
            command.add("--model");
            command.add(model);
        });
        configuration.reasoningSelection().explicitEffort().ifPresent(effort -> {
            command.add("--config");
            command.add("model_reasoning_effort=\"" + effort.configValue() + "\"");
        });
        command.add("-");
        return List.copyOf(command);
    }

    private byte[] encodePrompt(AiRequest request) throws JsonProcessingException {
        PromptEnvelope envelope = new PromptEnvelope(
                request.invocationId(),
                request.instruction(),
                request.context());
        return mapper.writeValueAsString(envelope).getBytes(StandardCharsets.UTF_8);
    }

    private static boolean shouldStore(AiRuntimeResult result) {
        return result.status() == AiRuntimeStatus.SUCCESS || result.tokenUsage().available();
    }

    private static AiFailureCode classifyFailure(String standardError) {
        String value = standardError.toLowerCase(Locale.ROOT);
        if (value.contains("authentication required")
                || value.contains("not logged in")
                || value.contains("please login")
                || value.contains("please log in")) {
            return AiFailureCode.AUTHENTICATION_REQUIRED;
        }
        if (value.contains("rate limit") || value.contains("too many requests")) {
            return AiFailureCode.RATE_LIMITED;
        }
        return AiFailureCode.EXECUTION_FAILED;
    }

    private static String safeVersion(String output) {
        String firstLine = output.lines().findFirst().orElse("").strip();
        StringBuilder safe = new StringBuilder();
        firstLine.codePoints()
                .filter(codePoint -> !Character.isISOControl(codePoint))
                .limit(128)
                .forEach(safe::appendCodePoint);
        return safe.toString();
    }

    private record PromptEnvelope(
            String invocationId,
            String instruction,
            Map<String, String> context) {}
}
