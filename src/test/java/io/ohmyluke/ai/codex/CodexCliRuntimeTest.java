package io.ohmyluke.ai.codex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.ohmyluke.ai.AiFailureCode;
import io.ohmyluke.ai.AiRequest;
import io.ohmyluke.ai.AiRuntimeResult;
import io.ohmyluke.ai.AiRuntimeStatus;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CodexCliRuntimeTest {
    @TempDir
    Path project;

    @Test
    void invokesCodexThroughArgumentsAndParsesFinalMessageAndUsage() throws Exception {
        Path arguments = project.resolve("arguments.txt");
        Path prompt = project.resolve("prompt.txt");
        Path executable = executable("""
                printf '%%s\\n' "$@" > %s
                cat > %s
                printf '%%s\\n' \
                  '{"type":"thread.started","thread_id":"thread-123"}' \
                  '{"type":"turn.started"}' \
                  '{"type":"item.completed","item":{"id":"item-1","type":"agent_message","text":"OML_OK"}}' \
                  '{"type":"turn.completed","usage":{"input_tokens":100,"cached_input_tokens":40,"output_tokens":20,"reasoning_output_tokens":5}}'
                """.formatted(shellPath(arguments), shellPath(prompt)));
        CodexCliRuntime runtime = new CodexCliRuntime(CodexCliConfiguration
                .forExecutable(project, executable)
                .withTimeout(Duration.ofSeconds(5)));

        AiRuntimeResult result = runtime.invoke(request("call-1", "답해줘"));

        assertEquals(AiRuntimeStatus.SUCCESS, result.status(), result.toString());
        assertEquals("OML_OK", result.output());
        assertEquals(120, result.usage());
        assertTrue(result.tokenUsage().available());
        assertEquals(100, result.tokenUsage().inputTokens());
        assertEquals(40, result.tokenUsage().cachedInputTokens());
        assertEquals(20, result.tokenUsage().outputTokens());
        assertEquals(5, result.tokenUsage().reasoningOutputTokens());
        assertEquals("codex-exec-jsonl", result.tokenUsage().source());
        assertEquals("thread-123", result.runtimeSessionId());

        String forwarded = Files.readString(arguments);
        assertTrue(forwarded.contains("exec\n"));
        assertTrue(forwarded.contains("--json\n"));
        assertTrue(forwarded.contains("--ephemeral\n"));
        assertTrue(forwarded.contains("--sandbox\nread-only\n"));
        assertTrue(forwarded.contains("--skip-git-repo-check\n"));
        assertFalse(forwarded.contains("--model\n"));
        assertFalse(forwarded.contains("model_reasoning_effort"));

        String sentPrompt = Files.readString(prompt);
        assertTrue(sentPrompt.contains("\"invocationId\":\"call-1\""));
        assertTrue(sentPrompt.contains("\"instruction\":\"답해줘\""));
        assertTrue(sentPrompt.contains("\"ticket\":\"42\""));
    }

    @Test
    void forwardsOnlyExplicitModelAndReasoningOverrides() throws Exception {
        Path arguments = project.resolve("explicit-arguments.txt");
        Path executable = successfulExecutable(arguments, "EXPLICIT_OK");
        CodexCliConfiguration configuration = CodexCliConfiguration
                .forExecutable(project, executable)
                .withModel("gpt-test-codex")
                .withReasoning(CodexReasoningEffort.XHIGH);

        AiRuntimeResult result = new CodexCliRuntime(configuration)
                .invoke(request("call-2", "explicit"));

        assertEquals(AiRuntimeStatus.SUCCESS, result.status(), result.toString());
        String forwarded = Files.readString(arguments);
        assertTrue(forwarded.contains("--model\ngpt-test-codex\n"));
        assertTrue(forwarded.contains("--config\nmodel_reasoning_effort=\"xhigh\"\n"));
    }

    @Test
    void reusesStoredResultAndRejectsChangedRequestForTheSameInvocation() throws Exception {
        Path count = project.resolve("count.txt");
        Path executable = executable("""
                if [ -f %s ]; then count=$(cat %s); else count=0; fi
                count=$((count + 1))
                printf '%%s' "$count" > %s
                cat >/dev/null
                printf '%%s\\n' \
                  '{"type":"item.completed","item":{"type":"agent_message","text":"CACHED"}}' \
                  '{"type":"turn.completed","usage":{"input_tokens":3,"cached_input_tokens":0,"output_tokens":2,"reasoning_output_tokens":0}}'
                """.formatted(shellPath(count), shellPath(count), shellPath(count)));
        CodexCliRuntime runtime = new CodexCliRuntime(
                CodexCliConfiguration.forExecutable(project, executable));

        AiRuntimeResult first = runtime.invoke(request("same-call", "first"));
        AiRuntimeResult replay = runtime.invoke(request("same-call", "first"));
        AiRuntimeResult conflict = runtime.invoke(request("same-call", "changed"));

        assertEquals("CACHED", first.output());
        assertEquals(first, replay);
        assertEquals("1", Files.readString(count));
        assertEquals(AiRuntimeStatus.FAILURE, conflict.status());
        assertEquals(AiFailureCode.REQUEST_CONFLICT, conflict.failure().code());
        assertEquals("1", Files.readString(count));
    }

    @Test
    void classifiesTimeoutInvalidJsonAndAuthenticationWithoutPersistingStderr() throws Exception {
        Path timeoutProject = Files.createDirectory(project.resolve("timeout-project"));
        Path timeoutExecutable = executable("sleep 2");
        AiRuntimeResult timeout = new CodexCliRuntime(CodexCliConfiguration
                .forExecutable(timeoutProject, timeoutExecutable)
                .withTimeout(Duration.ofMillis(100)))
                .invoke(request("timeout", "timeout"));
        assertEquals(AiFailureCode.TIMED_OUT, timeout.failure().code());

        Path invalidProject = Files.createDirectory(project.resolve("invalid-project"));
        Path invalidExecutable = executable("printf 'not-json\\n'");
        AiRuntimeResult invalid = new CodexCliRuntime(
                CodexCliConfiguration.forExecutable(invalidProject, invalidExecutable))
                .invoke(request("invalid", "invalid"));
        assertEquals(AiFailureCode.INVALID_RESPONSE, invalid.failure().code());

        Path authenticationProject = Files.createDirectory(project.resolve("auth-project"));
        Path authenticationExecutable = executable("""
                printf 'authentication required: secret-token-should-not-escape\\n' >&2
                exit 1
                """);
        AiRuntimeResult authentication = new CodexCliRuntime(
                CodexCliConfiguration.forExecutable(authenticationProject, authenticationExecutable))
                .invoke(request("auth", "auth"));
        assertEquals(AiFailureCode.AUTHENTICATION_REQUIRED, authentication.failure().code());
        assertFalse(authentication.failure().publicCause().contains("secret-token"));
    }

    @Test
    void keepsSuccessfulResponseWhenTokenUsageSchemaIsIncomplete() throws Exception {
        Path executable = executable("""
                cat >/dev/null
                printf '%s\\n' \
                  '{"type":"item.completed","item":{"type":"agent_message","text":"STILL_OK"}}' \
                  '{"type":"turn.completed","usage":{"input_tokens":3,"output_tokens":2}}'
                """);

        AiRuntimeResult result = new CodexCliRuntime(
                CodexCliConfiguration.forExecutable(project, executable))
                .invoke(request("partial-usage", "partial"));

        assertEquals(AiRuntimeStatus.SUCCESS, result.status(), result.toString());
        assertEquals("STILL_OK", result.output());
        assertFalse(result.tokenUsage().available());
        assertEquals(0, result.usage());
    }

    @Test
    void keepsSuccessfulResponseWhenTokenUsageSubsetsAreInconsistent() throws Exception {
        Path executable = executable("""
                cat >/dev/null
                printf '%s\\n' \
                  '{"type":"item.completed","item":{"type":"agent_message","text":"STILL_OK"}}' \
                  '{"type":"turn.completed","usage":{"input_tokens":3,"cached_input_tokens":4,"output_tokens":2,"reasoning_output_tokens":3}}'
                """);

        AiRuntimeResult result = new CodexCliRuntime(
                CodexCliConfiguration.forExecutable(project, executable))
                .invoke(request("inconsistent-usage", "inconsistent"));

        assertEquals(AiRuntimeStatus.SUCCESS, result.status(), result.toString());
        assertEquals("STILL_OK", result.output());
        assertFalse(result.tokenUsage().available());
        assertEquals(0, result.usage());
    }

    @Test
    void terminatesDescendantsAfterCodexExitsNormally() throws Exception {
        Path childPid = project.resolve("child.pid");
        Path executable = executable("""
                sleep 30 &
                child=$!
                printf '%%s' "$child" > %s
                sleep 0.1
                cat >/dev/null
                printf '%%s\\n' \
                  '{"type":"item.completed","item":{"type":"agent_message","text":"NO_ORPHAN"}}' \
                  '{"type":"turn.completed","usage":{"input_tokens":1,"cached_input_tokens":0,"output_tokens":1,"reasoning_output_tokens":0}}'
                """.formatted(shellPath(childPid)));

        AiRuntimeResult result = new CodexCliRuntime(CodexCliConfiguration
                .forExecutable(project, executable)
                .withTimeout(Duration.ofSeconds(5)))
                .invoke(request("child-cleanup", "cleanup"));

        assertEquals(AiRuntimeStatus.SUCCESS, result.status(), result.toString());
        long pid = Long.parseLong(Files.readString(childPid));
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
    }

    @Test
    void returnsPromptlyWhenACompletedParentLeavesAnOutputPipeOpen() throws Exception {
        Path childPid = project.resolve("fast-child.pid");
        Path executable = executable("""
                cat >/dev/null
                sleep 30 &
                child=$!
                printf '%%s' "$child" > %s
                printf '%%s\\n' \
                  '{"type":"item.completed","item":{"type":"agent_message","text":"BOUNDED"}}' \
                  '{"type":"turn.completed","usage":{"input_tokens":1,"cached_input_tokens":0,"output_tokens":1,"reasoning_output_tokens":0}}'
                """.formatted(shellPath(childPid)));

        long started = System.nanoTime();
        AiRuntimeResult result = new CodexCliRuntime(CodexCliConfiguration
                .forExecutable(project, executable)
                .withTimeout(Duration.ofSeconds(5)))
                .invoke(request("fast-child-cleanup", "cleanup"));
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        try {
            assertEquals(AiRuntimeStatus.SUCCESS, result.status(), result.toString());
            assertTrue(elapsedMillis < 3000, "runner exceeded its bounded cleanup window");
        } finally {
            if (Files.exists(childPid)) {
                ProcessHandle.of(Long.parseLong(Files.readString(childPid)))
                        .filter(ProcessHandle::isAlive)
                        .ifPresent(ProcessHandle::destroyForcibly);
            }
        }
    }

    @Test
    void failsClosedWhenCodexOutputExceedsTheConfiguredLimit() throws Exception {
        Path executable = executable("""
                cat >/dev/null
                i=0
                while [ "$i" -lt 1000 ]; do printf '0123456789'; i=$((i + 1)); done
                """);
        AiRuntimeResult result = new CodexCliRuntime(CodexCliConfiguration
                .forExecutable(project, executable)
                .withMaxOutputBytes(128))
                .invoke(request("large", "large"));

        assertEquals(AiFailureCode.OUTPUT_LIMIT_EXCEEDED, result.failure().code());
    }

    @Test
    void rejectsOversizedInputBeforeStartingCodex() throws Exception {
        Path marker = project.resolve("input-started.txt");
        Path executable = executable("printf started > " + shellPath(marker));

        AiRuntimeResult result = new CodexCliRuntime(CodexCliConfiguration
                .forExecutable(project, executable)
                .withMaxInputBytes(10))
                .invoke(request("large-input", "this prompt is larger than ten bytes"));

        assertEquals(AiFailureCode.INPUT_LIMIT_EXCEEDED, result.failure().code());
        assertFalse(Files.exists(marker));
    }

    @Test
    void concurrentRuntimesExecuteTheSameInvocationOnlyOnce() throws Exception {
        Path count = project.resolve("concurrent-count.txt");
        Path executable = executable("""
                if [ -f %s ]; then count=$(cat %s); else count=0; fi
                count=$((count + 1))
                printf '%%s' "$count" > %s
                cat >/dev/null
                sleep 0.2
                printf '%%s\\n' \
                  '{"type":"item.completed","item":{"type":"agent_message","text":"ONCE"}}' \
                  '{"type":"turn.completed","usage":{"input_tokens":1,"cached_input_tokens":0,"output_tokens":1,"reasoning_output_tokens":0}}'
                """.formatted(shellPath(count), shellPath(count), shellPath(count)));
        CodexCliConfiguration configuration = CodexCliConfiguration.forExecutable(project, executable);
        CodexCliRuntime firstRuntime = new CodexCliRuntime(configuration);
        CodexCliRuntime secondRuntime = new CodexCliRuntime(configuration);
        AiRequest request = request("concurrent-call", "same");

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> firstRuntime.invoke(request));
            var second = executor.submit(() -> secondRuntime.invoke(request));

            assertEquals(AiRuntimeStatus.SUCCESS, first.get(5, TimeUnit.SECONDS).status());
            assertEquals(AiRuntimeStatus.SUCCESS, second.get(5, TimeUnit.SECONDS).status());
        }
        assertEquals("1", Files.readString(count));
    }

    @Test
    void operatingSystemLockPreventsDuplicateInvocationAcrossJvmProcesses() throws Exception {
        Path executions = project.resolve("cross-jvm-executions.txt");
        Path executable = executable("""
                printf 'x' >> %s
                sleep 0.3
                cat >/dev/null
                printf '%%s\\n' \
                  '{"type":"item.completed","item":{"type":"agent_message","text":"CROSS_JVM"}}' \
                  '{"type":"turn.completed","usage":{"input_tokens":1,"cached_input_tokens":0,"output_tokens":1,"reasoning_output_tokens":0}}'
                """.formatted(shellPath(executions)));
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = System.getProperty("java.class.path");
        Process first = new ProcessBuilder(
                        java,
                        "-cp",
                        classpath,
                        CodexInvocationProcessFixture.class.getName(),
                        project.toString(),
                        executable.toString(),
                        "cross-jvm-call")
                .start();
        Process second = new ProcessBuilder(
                        java,
                        "-cp",
                        classpath,
                        CodexInvocationProcessFixture.class.getName(),
                        project.toString(),
                        executable.toString(),
                        "cross-jvm-call")
                .start();
        try {
            assertTrue(first.waitFor(10, TimeUnit.SECONDS));
            assertTrue(second.waitFor(10, TimeUnit.SECONDS));
            assertEquals(0, first.exitValue(), new String(first.getErrorStream().readAllBytes()));
            assertEquals(0, second.exitValue(), new String(second.getErrorStream().readAllBytes()));
            assertEquals("x", Files.readString(executions));
        } finally {
            first.destroyForcibly();
            second.destroyForcibly();
        }
    }

    @Test
    void operatingSystemLockRejectsAnotherJvmWhileTheChildJvmHoldsIt() throws Exception {
        Path firstEntered = project.resolve("first-entered");
        Path release = project.resolve("release");
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = System.getProperty("java.class.path");
        Process first = new ProcessBuilder(
                        java,
                        "-cp",
                        classpath,
                        CodexInvocationStoreProcessFixture.class.getName(),
                        project.toString(),
                        firstEntered.toString(),
                        release.toString())
                .start();
        try {
            awaitFile(firstEntered, first);
            Path lockPath = project.toRealPath()
                    .resolve(".oml/runtime/codex/invocations")
                    .resolve(CodexHashing.safeFileId("cross-jvm-lock") + ".lock");
            try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.WRITE)) {
                FileLock competing = channel.tryLock();
                try {
                    assertNull(competing, "another JVM acquired an already-held OS lock");
                } finally {
                    if (competing != null) {
                        competing.release();
                    }
                }
            }
            Files.writeString(release, "release");

            assertTrue(first.waitFor(10, TimeUnit.SECONDS));
            assertEquals(0, first.exitValue(), new String(first.getErrorStream().readAllBytes()));
            try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.WRITE);
                    FileLock acquired = channel.tryLock()) {
                assertNotNull(acquired, "OS lock remained held after the child JVM exited");
            }
        } finally {
            first.destroyForcibly();
        }
    }

    @Test
    void probesOfficialVersionAndLoginCommandsWithoutReadingCredentialFiles() throws Exception {
        Path arguments = project.resolve("probe-arguments.txt");
        Path executable = executable("""
                printf '%%s\\n' "$@" >> %s
                if [ "$1" = "--version" ]; then
                  printf 'codex-cli 1.2.3\\n'
                  exit 0
                fi
                if [ "$1" = "login" ] && [ "$2" = "status" ]; then
                  printf 'Logged in using ChatGPT\\n'
                  exit 0
                fi
                exit 2
                """.formatted(shellPath(arguments)));
        CodexCliRuntime runtime = new CodexCliRuntime(
                CodexCliConfiguration.forExecutable(project, executable));

        CodexRuntimeProbe probe = runtime.probe();

        assertTrue(probe.installed());
        assertTrue(probe.authenticated());
        assertEquals("codex-cli 1.2.3", probe.version());
        assertEquals("--version\nlogin\nstatus\n", Files.readString(arguments));
    }

    @Test
    void allowsOnlyEnvironmentNeededForSavedCliLoginAndConnectivity() {
        Map<String, String> environment = new HashMap<>(Map.of(
                "HOME", "/safe/home",
                "CODEX_HOME", "/safe/codex",
                "PATH", "/usr/bin",
                "OPENAI_API_KEY", "secret",
                "GITHUB_TOKEN", "secret",
                "AWS_SECRET_ACCESS_KEY", "secret",
                "STRIPE_SECRET_KEY", "secret",
                "DATABASE_URL", "postgres://user:password@database.example/app",
                "UNRELATED_FLAG", "value",
                "HTTPS_PROXY", "https://user:password@proxy.example"));
        environment.put("HTTP_PROXY", "http://proxy.example");
        environment.put("ALL_PROXY", "user:password@proxy.example:1080");

        CodexProcessRunner.restrictEnvironment(environment);

        assertEquals("/safe/home", environment.get("HOME"));
        assertEquals("/safe/codex", environment.get("CODEX_HOME"));
        assertEquals("/usr/bin", environment.get("PATH"));
        assertEquals("http://proxy.example", environment.get("HTTP_PROXY"));
        assertFalse(environment.containsKey("OPENAI_API_KEY"));
        assertFalse(environment.containsKey("GITHUB_TOKEN"));
        assertFalse(environment.containsKey("AWS_SECRET_ACCESS_KEY"));
        assertFalse(environment.containsKey("STRIPE_SECRET_KEY"));
        assertFalse(environment.containsKey("DATABASE_URL"));
        assertFalse(environment.containsKey("UNRELATED_FLAG"));
        assertFalse(environment.containsKey("HTTPS_PROXY"));
        assertFalse(environment.containsKey("ALL_PROXY"));

        Map<String, String> malformedPorts = new HashMap<>(Map.of(
                "HTTP_PROXY", "http://proxy.example:",
                "HTTPS_PROXY", "https://proxy.example:65536",
                "ALL_PROXY", "socks5://proxy.example:0",
                "NO_PROXY", "localhost,127.0.0.1"));
        CodexProcessRunner.restrictEnvironment(malformedPorts);
        assertFalse(malformedPorts.containsKey("HTTP_PROXY"));
        assertFalse(malformedPorts.containsKey("HTTPS_PROXY"));
        assertFalse(malformedPorts.containsKey("ALL_PROXY"));
        assertEquals("localhost,127.0.0.1", malformedPorts.get("NO_PROXY"));
    }

    @Test
    void refusesInvocationStorageThatEscapesThroughAnOmlSymlink() throws Exception {
        Path safeProject = Files.createDirectory(project.resolve("symlink-project"));
        Path outside = Files.createDirectory(project.resolve("outside-store"));
        try {
            Files.createSymbolicLink(safeProject.resolve(".oml"), outside);
        } catch (UnsupportedOperationException | IOException error) {
            assumeTrue(false, "symbolic links are unavailable");
        }
        Path marker = project.resolve("symlink-executed.txt");
        Path executable = executable("printf executed > " + shellPath(marker));

        AiRuntimeResult result = new CodexCliRuntime(
                CodexCliConfiguration.forExecutable(safeProject, executable))
                .invoke(request("symlink-store", "safe"));

        assertEquals(AiFailureCode.INVALID_RESPONSE, result.failure().code());
        assertFalse(Files.exists(marker));
    }

    private Path successfulExecutable(Path arguments, String response) throws IOException {
        return executable("""
                printf '%%s\\n' "$@" > %s
                cat >/dev/null
                printf '%%s\\n' \
                  '{"type":"item.completed","item":{"type":"agent_message","text":"%s"}}' \
                  '{"type":"turn.completed","usage":{"input_tokens":1,"cached_input_tokens":0,"output_tokens":1,"reasoning_output_tokens":0}}'
                """.formatted(shellPath(arguments), response));
    }

    private static void awaitFile(Path path, Process process) throws Exception {
        for (int attempt = 0;
                attempt < 500 && Files.notExists(path) && process.isAlive();
                attempt++) {
            Thread.sleep(10);
        }
        String error = process.isAlive()
                ? "child JVM did not reach the expected barrier"
                : new String(process.getErrorStream().readAllBytes());
        assertTrue(Files.exists(path), error);
    }

    private Path executable(String body) throws IOException {
        Path executable = Files.createTempFile(project, "fake-codex-", ".sh");
        Files.writeString(executable, "#!/bin/sh\nset -eu\n" + body, StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(executable, Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE));
        return executable;
    }

    private static AiRequest request(String invocationId, String instruction) {
        return new AiRequest(invocationId, instruction, Map.of("ticket", "42"));
    }

    private static String shellPath(Path path) {
        return "'" + path.toAbsolutePath().toString().replace("'", "'\\''") + "'";
    }
}
