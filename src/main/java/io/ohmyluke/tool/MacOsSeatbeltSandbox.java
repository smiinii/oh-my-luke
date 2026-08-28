package io.ohmyluke.tool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** macOS Seatbelt launcher with a deny-by-default filesystem and network profile. */
public final class MacOsSeatbeltSandbox implements ProcessSandbox {
    private static final Path SANDBOX_EXEC = Path.of("/usr/bin/sandbox-exec");
    private static final Path SUPERVISOR_SHELL = Path.of("/bin/sh");
    private static final String SUPERVISOR = String.join(
            " ",
            "exec 3>&2 2>/dev/null;",
            "set -m;",
            "\"$@\" 2>&3 & oml_pid=$!;",
            "trap 'kill -KILL -$oml_pid 2>/dev/null' EXIT INT TERM HUP;",
            "wait $oml_pid; oml_status=$?;",
            "kill -KILL -$oml_pid 2>/dev/null;",
            "trap - EXIT;",
            "exit $oml_status");

    @Override
    public boolean available() {
        return Files.isExecutable(SANDBOX_EXEC);
    }

    @Override
    public String unavailableReason() {
        return available() ? "" : "/usr/bin/sandbox-exec is unavailable";
    }

    @Override
    public SandboxLaunch prepare(ProcessSandboxSpec specification) {
        if (!available()) {
            throw new ProcessToolException(unavailableReason());
        }
        try {
            Path profile = Files.createTempFile("oml-seatbelt-", ".sb");
            Files.writeString(profile, profile(specification));
            ArrayList<String> command = new ArrayList<>();
            // This is a fixed OML-owned wrapper, not caller-provided shell text.
            // Job control places the requested process and all of its children in
            // one process group outside Seatbelt, so cleanup signals cannot be
            // blocked by the sandboxed program's signal policy.
            command.add(SUPERVISOR_SHELL.toString());
            command.add("-c");
            command.add(SUPERVISOR);
            command.add("oml-process-supervisor");
            command.add(SANDBOX_EXEC.toString());
            command.add("-f");
            command.add(profile.toString());
            command.add(specification.executable().toString());
            command.addAll(specification.arguments());
            return new SandboxLaunch(command, profile);
        } catch (IOException error) {
            throw new ProcessToolException("failed to create the macOS sandbox profile", error);
        }
    }

    private static String profile(ProcessSandboxSpec specification) {
        String workspace = quote(specification.workspaceRoot());
        String home = quote(specification.isolatedHome());
        String executable = quote(specification.executable());
        StringBuilder profile = new StringBuilder();
        profile.append("(version 1)\n");
        profile.append("(deny default)\n");
        if (specification.networkAllowed()) {
            profile.append("(allow process-exec (literal \"").append(executable).append("\"))\n");
        } else {
            profile.append("(allow process*)\n");
        }
        profile.append("(allow signal (target self))\n");
        profile.append("(allow sysctl-read)\n");
        profile.append("(allow mach-lookup)\n");
        profile.append("(allow ipc-posix-shm)\n");
        // Seatbelted launchers and dynamic loaders inspect path metadata outside the
        // content allowlist. Metadata alone reveals no file contents; file-read-data
        // remains limited to the runtime, executable, workspace, and isolated home.
        profile.append("(allow file-read-metadata)\n");
        for (String runtimeLink : List.of("/", "/private", "/tmp", "/var", "/etc")) {
            profile.append("(allow file-read* (literal \"")
                    .append(escape(runtimeLink))
                    .append("\"))\n");
        }
        for (String runtimeRoot : List.of(
                "/System",
                "/usr",
                "/bin",
                "/sbin",
                "/Library",
                "/private/etc",
                "/private/var",
                "/private/preboot",
                "/dev",
                "/Applications",
                "/opt",
                "/cores")) {
            if (Files.exists(Path.of(runtimeRoot))) {
                profile.append("(allow file-read* (subpath \"")
                        .append(escape(runtimeRoot))
                        .append("\"))\n");
            }
        }
        for (String deniedRuntimeData : List.of("/private/var/folders", "/private/var/tmp")) {
            if (Files.exists(Path.of(deniedRuntimeData))) {
                profile.append("(deny file-read* (subpath \"")
                        .append(escape(deniedRuntimeData))
                        .append("\"))\n");
            }
        }
        profile.append("(allow file-read* (literal \"").append(executable).append("\"))\n");
        profile.append("(allow file-read* (subpath \"").append(workspace).append("\"))\n");
        profile.append("(allow file-read* (subpath \"").append(home).append("\"))\n");
        profile.append("(allow file-write* (subpath \"").append(workspace).append("\"))\n");
        profile.append("(allow file-write* (subpath \"").append(home).append("\"))\n");
        profile.append("(allow file-write* (literal \"/dev/null\"))\n");
        if (specification.networkAllowed()) {
            profile.append("(allow network*)\n");
        }
        return profile.toString();
    }

    private static String quote(Path path) {
        return escape(path.toAbsolutePath().normalize().toString());
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
