package io.ohmyluke.tool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** macOS Seatbelt launcher with a deny-by-default filesystem and network profile. */
public final class MacOsSeatbeltSandbox implements ProcessSandbox {
    private static final Path SANDBOX_EXEC = Path.of("/usr/bin/sandbox-exec");

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
        // Seatbelt has no job object or PID namespace. Until a native supervisor
        // exists, only the exact initial executable is allowed to prevent daemon
        // and setsid descendants from outliving OML.
        profile.append("(allow process-exec (literal \"").append(executable).append("\"))\n");
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
        for (String runtimeRoot : runtimeRoots(specification.executable())) {
            if (Files.exists(Path.of(runtimeRoot))) {
                profile.append("(allow file-read* (subpath \"")
                        .append(escape(runtimeRoot))
                        .append("\"))\n");
            }
        }
        for (String deniedRuntimeData : List.of(
                "/private/var/folders",
                "/private/var/tmp",
                "/opt/homebrew/etc",
                "/opt/homebrew/var",
                "/usr/local/etc",
                "/usr/local/var")) {
            profile.append("(deny file-read* (subpath \"")
                    .append(escape(deniedRuntimeData))
                    .append("\"))\n");
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

    private static List<String> runtimeRoots(Path executable) {
        ArrayList<String> roots = new ArrayList<>(List.of(
                "/System",
                "/usr/bin",
                "/usr/lib",
                "/usr/libexec",
                "/usr/sbin",
                "/usr/share",
                "/bin",
                "/sbin",
                "/Library/Developer",
                "/Library/Java/JavaVirtualMachines",
                "/private/etc/hosts",
                "/private/etc/passwd",
                "/private/etc/protocols",
                "/private/etc/resolv.conf",
                "/private/etc/services",
                "/private/etc/ssl",
                "/private/var/db/dyld",
                "/private/var/db/timezone",
                "/private/var/select",
                "/dev",
                "/opt/homebrew/Cellar",
                "/usr/local/Cellar"));
        try {
            Path realExecutable = executable.toRealPath();
            Path current = realExecutable.getParent();
            while (current != null) {
                String name = current.getFileName() == null ? "" : current.getFileName().toString();
                if (name.equals("Home") && current.getParent() != null
                        && current.getParent().getFileName() != null
                        && current.getParent().getFileName().toString().equals("Contents")) {
                    roots.add(current.toString());
                    break;
                }
                if (name.equals("Contents") && current.getParent() != null
                        && current.getParent().getFileName() != null
                        && current.getParent().getFileName().toString().endsWith(".app")) {
                    roots.add(current.toString());
                    break;
                }
                current = current.getParent();
            }
        } catch (IOException ignored) {
            // The caller already resolves executables; any later change fails closed.
        }
        return List.copyOf(roots);
    }

    private static String quote(Path path) {
        return escape(path.toAbsolutePath().normalize().toString());
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
