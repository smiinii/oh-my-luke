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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.LockSupport;

final class CodexProcessRunner {
    private static final Set<String> ALLOWED_ENVIRONMENT = Set.of(
            "PATH",
            "HOME",
            "USERPROFILE",
            "CODEX_HOME",
            "TMPDIR",
            "TMP",
            "TEMP",
            "LANG",
            "LANGUAGE",
            "LC_ALL",
            "LC_CTYPE",
            "TZ",
            "SYSTEMROOT",
            "WINDIR",
            "COMSPEC",
            "PATHEXT",
            "XDG_CONFIG_HOME",
            "XDG_CACHE_HOME",
            "SSL_CERT_FILE",
            "SSL_CERT_DIR",
            "NODE_EXTRA_CA_CERTS",
            "HTTP_PROXY",
            "HTTPS_PROXY",
            "ALL_PROXY",
            "NO_PROXY");

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
            restrictEnvironment(builder.environment());
            process = builder.start();
        } catch (IOException error) {
            return new CodexProcessResult(false, false, -1, "", "", false, false);
        }

        try (ExecutorService tasks = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<CapturedOutput> stdout = tasks.submit(
                    () -> capture(process.getInputStream(), outputLimit));
            Future<CapturedOutput> stderr = tasks.submit(
                    () -> capture(process.getErrorStream(), outputLimit));
            Set<ProcessHandle> descendants = ConcurrentHashMap.newKeySet();
            CountDownLatch observerReady = new CountDownLatch(1);
            Future<?> descendantObserver = tasks.submit(
                    () -> observeDescendants(process, descendants, observerReady));
            awaitObserverReady(observerReady);
            observeBeforeInput(process, descendants);
            Future<Boolean> inputWritten = tasks.submit(() -> writeInput(process, input));
            boolean completed = waitFor(process, timeout, descendants);
            if (!completed) {
                terminateTree(process, descendants);
            }
            awaitDescendantObserver(descendantObserver);
            terminateDescendants(process, descendants);
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

    private static void observeBeforeInput(
            Process process,
            Set<ProcessHandle> descendants) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(100);
        while (process.isAlive() && System.nanoTime() < deadline) {
            process.descendants().forEach(descendants::add);
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }
    }

    private static void observeDescendants(
            Process process,
            Set<ProcessHandle> descendants,
            CountDownLatch ready) {
        ready.countDown();
        while (process.isAlive()) {
            process.descendants().forEach(descendants::add);
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
            if (Thread.interrupted()) {
                return;
            }
        }
        process.descendants().forEach(descendants::add);
    }

    private static void awaitObserverReady(CountDownLatch ready) {
        try {
            ready.await(1, TimeUnit.SECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }

    private static void awaitDescendantObserver(Future<?> observer) {
        try {
            observer.get(1, TimeUnit.SECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException error) {
            observer.cancel(true);
        }
    }

    private static boolean waitFor(
            Process process,
            Duration timeout,
            Set<ProcessHandle> descendants) {
        long deadline = System.nanoTime() + timeout.toNanos();
        try {
            while (process.isAlive()) {
                process.descendants().forEach(descendants::add);
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    return false;
                }
                long slice = Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(25));
                process.waitFor(slice, TimeUnit.NANOSECONDS);
            }
            process.descendants().forEach(descendants::add);
            return true;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return false;
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

    private static void terminateTree(Process process, Set<ProcessHandle> observedDescendants) {
        terminateDescendants(process, observedDescendants);
        process.destroyForcibly();
        try {
            process.waitFor(5, TimeUnit.SECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }

    private static void terminateDescendants(
            Process process,
            Set<ProcessHandle> observedDescendants) {
        process.descendants().forEach(observedDescendants::add);
        List<ProcessHandle> descendants = new ArrayList<>(observedDescendants);
        for (int index = descendants.size() - 1; index >= 0; index--) {
            ProcessHandle descendant = descendants.get(index);
            if (descendant.isAlive()) {
                descendant.destroyForcibly();
            }
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        for (ProcessHandle descendant : descendants) {
            awaitTermination(descendant, deadline);
        }
    }

    private static void awaitTermination(ProcessHandle process, long deadline) {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
            return;
        }
        try {
            process.onExit().get(remaining, TimeUnit.NANOSECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException ignored) {
            // The caller still returns a bounded, sanitized failure or result.
        }
    }

    static void restrictEnvironment(Map<String, String> environment) {
        environment.entrySet().removeIf(entry -> {
            String name = entry.getKey().toUpperCase(Locale.ROOT);
            if (!ALLOWED_ENVIRONMENT.contains(name)) {
                return true;
            }
            return containsUriUserInfo(entry.getValue());
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
