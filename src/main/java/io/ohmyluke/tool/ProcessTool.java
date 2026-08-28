package io.ohmyluke.tool;

import io.ohmyluke.policy.ToolPermission;
import io.ohmyluke.policy.ToolPermissionDecision;
import io.ohmyluke.policy.ToolPermissionEvaluator;
import io.ohmyluke.policy.ToolPermissionRequest;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

/** Runs explicit executables in a disposable project copy and a verified OS sandbox. */
public final class ProcessTool {
    private static final Set<String> SHELLS = Set.of(
            "sh", "bash", "zsh", "fish", "dash", "cmd", "cmd.exe", "powershell", "powershell.exe",
            "pwsh", "env", "busybox");
    private static final String DEFAULT_PATH = String.join(
            System.getProperty("path.separator"),
            "/usr/bin", "/bin", "/usr/sbin", "/sbin", "/opt/homebrew/bin", "/usr/local/bin");

    private final Path projectRoot;
    private final String runId;
    private final ToolPermissionEvaluator permissions;
    private final ProcessSandbox sandbox;
    private final SecretRedactor redactor = new SecretRedactor();

    public ProcessTool(
            Path projectRoot,
            String runId,
            ToolPermissionEvaluator permissions,
            ProcessSandbox sandbox) {
        try {
            this.projectRoot = Objects.requireNonNull(projectRoot, "projectRoot").toRealPath();
        } catch (IOException error) {
            throw new IllegalArgumentException("projectRoot must exist and resolve safely", error);
        }
        this.runId = requireText(runId, "runId");
        this.permissions = Objects.requireNonNull(permissions, "permissions");
        this.sandbox = Objects.requireNonNull(sandbox, "sandbox");
    }

    public ToolPermissionRequest permissionRequest(ProcessToolRequest request) {
        Objects.requireNonNull(request, "request");
        Path executable = resolveExecutable(request.executable());
        return new ToolPermissionRequest(
                request.operationId(),
                runId,
                projectRoot,
                request.capability(),
                ToolNodeSupport.processPermissionTarget(request, executable));
    }

    public ProcessToolResult execute(ProcessToolRequest request) {
        Objects.requireNonNull(request, "request");
        if (!sandbox.available()) {
            return denied("sandbox.unavailable", sandbox.unavailableReason());
        }

        Path realExecutable;
        try {
            realExecutable = resolveExecutable(request.executable());
        } catch (ProcessToolException error) {
            return denied("process.executable-unresolved", error.getMessage());
        }
        if (isShell(realExecutable)) {
            return denied("process.shell-deny", "Shells and command wrappers are not accepted");
        }
        if (request.capability() == io.ohmyluke.policy.ToolCapability.SECRET_USE) {
            return denied(
                    "process.credential-broker-required",
                    "Raw credentials are never injected; a scoped credential broker is required");
        }
        if (request.capability() == io.ohmyluke.policy.ToolCapability.DOCKER_ACCESS
                || request.capability() == io.ohmyluke.policy.ToolCapability.OUTSIDE_PROJECT_ACCESS) {
            return denied(
                    "process.capability-unavailable",
                    "The disposable process workspace does not expose host sockets or outside paths");
        }

        ToolPermissionDecision permission = permissions.evaluate(new ToolPermissionRequest(
                request.operationId(),
                runId,
                projectRoot,
                request.capability(),
                ToolNodeSupport.processPermissionTarget(request, realExecutable)));
        if (permission.permission() != ToolPermission.ALLOW) {
            return notExecuted(permission, permission.detail());
        }
        long started = System.nanoTime();
        try (ProcessWorkspace workspace = ProcessWorkspace.create(projectRoot, runId, request.operationId())) {
            Path executable = workspace.mapExecutable(realExecutable);
            Path workingDirectory = workspace.workingDirectory(request.workingDirectory());
            ProcessSandboxSpec specification = new ProcessSandboxSpec(
                    executable,
                    request.arguments(),
                    workspace.projectRoot(),
                    workingDirectory,
                    workspace.isolatedHome(),
                    request.networkRequested());
            try (SandboxLaunch launch = sandbox.prepare(specification)) {
                return runProcess(request, permission, workspace, workingDirectory, launch, started);
            }
        } catch (RuntimeException error) {
            return new ProcessToolResult(
                    permission,
                    false,
                    -1,
                    "",
                    "",
                    false,
                    false,
                    elapsedMillis(started),
                    "Process setup failed safely: " + error.getClass().getSimpleName());
        }
    }

    private ProcessToolResult runProcess(
            ProcessToolRequest request,
            ToolPermissionDecision permission,
            ProcessWorkspace workspace,
            Path workingDirectory,
            SandboxLaunch launch,
            long started) {
        ProcessBuilder builder = new ProcessBuilder(launch.command());
        builder.directory(workingDirectory.toFile());
        Map<String, String> environment = builder.environment();
        environment.clear();
        environment.put("HOME", workspace.isolatedHome().toString());
        environment.put("TMPDIR", workspace.isolatedHome().toString());
        environment.put("PATH", DEFAULT_PATH);
        environment.put("LANG", "C.UTF-8");
        environment.putAll(request.environment());

        Process process;
        try {
            process = builder.start();
        } catch (IOException error) {
            return new ProcessToolResult(
                    permission,
                    false,
                    -1,
                    "",
                    "",
                    false,
                    false,
                    elapsedMillis(started),
                    "Sandboxed process could not start: " + error.getClass().getSimpleName());
        }

        try (DescendantTracker descendants = new DescendantTracker(process);
                ExecutorService readers = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<CapturedOutput> stdout = readers.submit(
                    () -> capture(process.getInputStream(), request.maxOutputBytes()));
            Future<CapturedOutput> stderr = readers.submit(
                    () -> capture(process.getErrorStream(), request.maxOutputBytes()));
            boolean completed;
            try {
                completed = process.waitFor(request.timeout().toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                completed = false;
            }
            if (!completed) {
                terminateTree(process, descendants);
            } else {
                descendants.terminateTracked();
            }
            CapturedOutput standardOutput = awaitOutput(stdout);
            CapturedOutput standardError = awaitOutput(stderr);
            int exitCode = completed ? process.exitValue() : -1;
            return new ProcessToolResult(
                    permission,
                    true,
                    exitCode,
                    redactor.redact(standardOutput.text()),
                    redactor.redact(standardError.text()),
                    !completed,
                    standardOutput.truncated() || standardError.truncated(),
                    elapsedMillis(started),
                    completed ? "Sandboxed process completed" : "Sandboxed process reached its time limit");
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

    private static void terminateTree(Process process, DescendantTracker tracker) {
        tracker.terminateTracked();
        List<ProcessHandle> current = new ArrayList<>(process.descendants().toList());
        for (int index = current.size() - 1; index >= 0; index--) {
            current.get(index).destroyForcibly();
        }
        process.destroyForcibly();
        try {
            process.waitFor(5, TimeUnit.SECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean isShell(Path executable) {
        Path fileName = executable.getFileName();
        String name = fileName == null ? executable.toString() : fileName.toString();
        return SHELLS.contains(name.toLowerCase(Locale.ROOT));
    }

    private static Path resolveExecutable(Path executable) {
        try {
            Path real = Objects.requireNonNull(executable, "executable").toRealPath();
            if (!Files.isRegularFile(real) || !Files.isExecutable(real)) {
                throw new ProcessToolException("executable must be a regular executable file");
            }
            return real;
        } catch (IOException error) {
            throw new ProcessToolException("executable must exist and resolve safely", error);
        }
    }

    private static ProcessToolResult denied(String code, String detail) {
        return notExecuted(ToolPermissionDecision.deny(code, detail), detail);
    }

    private static ProcessToolResult notExecuted(ToolPermissionDecision permission, String detail) {
        return new ProcessToolResult(permission, false, -1, "", "", false, false, 0, detail);
    }

    private static long elapsedMillis(long started) {
        return Duration.ofNanos(System.nanoTime() - started).toMillis();
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private record CapturedOutput(String text, boolean truncated) {}

    private static final class DescendantTracker implements AutoCloseable {
        private final Process process;
        private final ConcurrentHashMap<Long, ProcessHandle> observed = new ConcurrentHashMap<>();
        private final AtomicBoolean running = new AtomicBoolean(true);
        private final Thread watcher;

        private DescendantTracker(Process process) {
            this.process = process;
            this.watcher = Thread.ofVirtual().name("oml-process-descendants").start(() -> {
                while (running.get() || process.isAlive()) {
                    capture();
                    LockSupport.parkNanos(java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(1));
                }
                capture();
            });
        }

        private void capture() {
            process.descendants().forEach(handle -> observed.putIfAbsent(handle.pid(), handle));
        }

        private void terminateTracked() {
            capture();
            List<ProcessHandle> handles = new ArrayList<>(observed.values());
            for (int index = handles.size() - 1; index >= 0; index--) {
                ProcessHandle handle = handles.get(index);
                if (handle.isAlive()) {
                    handle.destroyForcibly();
                }
            }
        }

        @Override
        public void close() {
            running.set(false);
            try {
                watcher.join(100);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
            terminateTracked();
        }
    }
}
