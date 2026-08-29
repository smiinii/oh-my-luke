package io.ohmyluke.ai.codex;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

final class CodexProcessRunner {
    CodexProcessResult run(
            List<String> command,
            Path workingDirectory,
            byte[] input,
            Duration timeout,
            int outputLimit) {
        Process process;
        try {
            ProcessBuilder builder = new ProcessBuilder(command)
                    .directory(workingDirectory.toFile());
            removeSecretEnvironment(builder.environment());
            process = builder.start();
        } catch (IOException error) {
            return new CodexProcessResult(false, false, -1, "", "", false, false);
        }

        try (ExecutorService tasks = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<CapturedOutput> stdout = tasks.submit(
                    () -> capture(process.getInputStream(), outputLimit));
            Future<CapturedOutput> stderr = tasks.submit(
                    () -> capture(process.getErrorStream(), outputLimit));
            Future<Boolean> inputWritten = tasks.submit(() -> writeInput(process, input));
            boolean completed;
            try {
                completed = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                completed = false;
            }
            if (!completed) {
                terminateTree(process);
            }
            boolean writeSucceeded = awaitWrite(inputWritten);
            CapturedOutput standardOutput = awaitOutput(stdout);
            CapturedOutput standardError = awaitOutput(stderr);
            int exitCode = completed ? process.exitValue() : -1;
            return new CodexProcessResult(
                    true,
                    !completed,
                    exitCode,
                    standardOutput.text(),
                    standardError.text(),
                    standardOutput.truncated() || standardError.truncated(),
                    !writeSucceeded);
        }
    }

    private static boolean writeInput(Process process, byte[] input) {
        try (OutputStream output = process.getOutputStream()) {
            output.write(input);
            output.flush();
            return true;
        } catch (IOException error) {
            return false;
        }
    }

    private static CapturedOutput capture(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(limit, 8192));
        byte[] buffer = new byte[8192];
        int total = 0;
        boolean truncated = false;
        int read;
        while ((read = input.read(buffer)) >= 0) {
            int remaining = limit - total;
            if (remaining > 0) {
                int copied = Math.min(remaining, read);
                output.write(buffer, 0, copied);
                total += copied;
            }
            if (read > remaining) {
                truncated = true;
            }
        }
        return new CapturedOutput(output.toString(StandardCharsets.UTF_8), truncated);
    }

    private static CapturedOutput awaitOutput(Future<CapturedOutput> future) {
        try {
            return future.get(5, TimeUnit.SECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return new CapturedOutput("", true);
        } catch (ExecutionException | TimeoutException error) {
            future.cancel(true);
            return new CapturedOutput("", true);
        }
    }

    private static boolean awaitWrite(Future<Boolean> future) {
        try {
            return future.get(5, TimeUnit.SECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException | TimeoutException error) {
            future.cancel(true);
            return false;
        }
    }

    private static void terminateTree(Process process) {
        List<ProcessHandle> descendants = new ArrayList<>(process.descendants().toList());
        for (int index = descendants.size() - 1; index >= 0; index--) {
            descendants.get(index).destroyForcibly();
        }
        process.destroyForcibly();
        try {
            process.waitFor(5, TimeUnit.SECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }

    static void removeSecretEnvironment(Map<String, String> environment) {
        environment.entrySet().removeIf(entry -> {
            String name = entry.getKey().toUpperCase(Locale.ROOT);
            if (name.endsWith("_API_KEY")
                    || name.endsWith("_TOKEN")
                    || name.endsWith("_SECRET")
                    || name.endsWith("_PASSWORD")
                    || name.endsWith("_PRIVATE_KEY")
                    || name.contains("_CREDENTIAL")
                    || name.equals("AWS_ACCESS_KEY_ID")
                    || name.equals("AWS_SESSION_TOKEN")) {
                return true;
            }
            return name.endsWith("_PROXY") && containsUriUserInfo(entry.getValue());
        });
    }

    private static boolean containsUriUserInfo(String value) {
        int scheme = value.indexOf("://");
        int at = value.indexOf('@', scheme + 3);
        if (scheme < 0 || at < 0) {
            return false;
        }
        int slash = value.indexOf('/', scheme + 3);
        return slash < 0 || at < slash;
    }

    private record CapturedOutput(String text, boolean truncated) {}
}
