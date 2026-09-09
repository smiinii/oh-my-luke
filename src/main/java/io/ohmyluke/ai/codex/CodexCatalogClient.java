package io.ohmyluke.ai.codex;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** A short-lived stdio metadata connection; all output is bounded and stderr is never displayed or persisted. */
public final class CodexCatalogClient {
    private final Runnable checkProcessObservation;
    public CodexCatalogClient() {
        this(() -> { try (var children = ProcessHandle.current().descendants()) { children.count(); } });
    }
    CodexCatalogClient(Runnable checkProcessObservation) { this.checkProcessObservation = java.util.Objects.requireNonNull(checkProcessObservation); }
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(16).maxStringLength(8192).build()).build())
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public CodexModelCatalog load(Path executable, Path cwd) {
        return load(List.of(executable.toString(), "app-server", "--listen", "stdio://"), cwd, Duration.ofSeconds(10));
    }

    CodexModelCatalog load(List<String> command, Path cwd, Duration timeout) {
        if (timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofSeconds(30)) > 0) {
            throw new IllegalArgumentException("catalog timeout must be 0..30 seconds");
        }
        // Do not start a process if this environment cannot inspect children for cleanup.
        try { checkProcessObservation.run(); }
        catch (RuntimeException error) { return CodexModelCatalog.unavailable(); }
        Process process;
        try {
            var builder = new ProcessBuilder(command).directory(cwd.toFile());
            CodexProcessRunner.restrictEnvironment(builder.environment());
            process = builder.start();
        } catch (IOException error) { return CodexModelCatalog.unavailable(); }
        var tasks = Executors.newVirtualThreadPerTaskExecutor();
        var descendants = new HashSet<ProcessHandle>();
        CodexModelCatalog result = CodexModelCatalog.unavailable();
        try {
            var stderr = tasks.submit(() -> {
                try {
                    int total = 0;
                    byte[] bytes = new byte[4096];
                    int count;
                    while ((count = process.getErrorStream().read(bytes)) != -1) {
                        if ((total += count) > 65_536) { process.destroyForcibly(); return false; }
                    }
                    return true;
                } catch (IOException ignored) { return false; }
            });
            var work = tasks.submit(() -> CodexModelCatalog.read(new Session(process)));
            long deadline = System.nanoTime() + timeout.toNanos();
            while (System.nanoTime() < deadline) {
                process.descendants().forEach(descendants::add);
                if (stderr.isDone() && !stderr.get()) { break; }
                try {
                    result = work.get(Math.min(25, Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()))), TimeUnit.MILLISECONDS);
                    break;
                }
                catch (TimeoutException ignored) { /* sample children while waiting for bounded protocol IO */ }
            }
        } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
        catch (Exception ignored) { /* no raw subprocess diagnostics */ }
        finally {
            try { process.getOutputStream().close(); } catch (IOException ignored) {}
            try { CodexProcessRunner.terminateTree(process, descendants); }
            catch (RuntimeException error) {
                process.destroyForcibly();
                result = CodexModelCatalog.unavailable();
            }
            try { process.getInputStream().close(); process.getErrorStream().close(); } catch (IOException ignored) {}
            tasks.shutdownNow();
        }
        return result;
    }

    private static final class Session implements CodexModelCatalog.Exchange {
        private final Process process;
        private int id;
        private int total;
        private int messages;
        private boolean accountObserved;
        Session(Process process) { this.process = process; }

        @Override public JsonNode call(String method, Map<String, Object> params) throws IOException {
            int requestId = ++id;
            boolean notification = method.equals("initialized");
            byte[] message = JSON.writeValueAsBytes(notification ? Map.of("method", method, "params", params)
                    : Map.of("id", requestId, "method", method, "params", params));
            process.getOutputStream().write(message); process.getOutputStream().write('\n'); process.getOutputStream().flush();
            if (notification) { return null; }
            while (++messages <= 128) {
                JsonNode reply = JSON.readTree(line());
                if (reply == null || !reply.isObject()) { throw new IOException("invalid reply"); }
                if (reply.has("method")) {
                    if (!reply.get("method").isTextual()) { throw new IOException("invalid notification"); }
                    if (reply.has("id") || (accountObserved && "account/updated".equals(reply.path("method").asText()))) {
                        throw new IOException("unexpected request or account changed");
                    }
                    continue;
                }
                if (!reply.path("id").isIntegralNumber() || !reply.path("id").canConvertToInt()
                        || reply.get("id").intValue() != requestId || reply.has("error") || !reply.has("result")) {
                    throw new IOException("invalid reply");
                }
                if (method.equals("account/read")) { accountObserved = true; }
                return reply.get("result");
            }
            throw new IOException("too many messages");
        }

        private byte[] line() throws IOException {
            var line = new ByteArrayOutputStream();
            int next;
            while ((next = process.getInputStream().read()) != -1) {
                if (++total > 512 * 1024 || line.size() >= 128 * 1024) { throw new IOException("output limit"); }
                if (next == '\n') { return line.toByteArray(); }
                line.write(next);
            }
            throw new IOException("incomplete reply");
        }
    }
}
