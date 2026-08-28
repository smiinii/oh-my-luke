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
        profile.append("(allow process*)\n");
        profile.append("(allow signal (target self))\n");
        profile.append("(allow sysctl-read)\n");
        profile.append("(allow mach-lookup)\n");
        profile.append("(allow ipc-posix-shm)\n");
        profile.append("(allow file-read*)\n");
        for (String protectedRoot : List.of(
                "/Users", "/home", "/root", "/Volumes", "/Network", "/private/var/folders")) {
            if (Files.exists(Path.of(protectedRoot))) {
                profile.append("(deny file-read* (subpath \"")
                        .append(escape(protectedRoot))
                        .append("\"))\n");
            }
        }
        profile.append("(allow file-read* (literal \"").append(executable).append("\"))\n");
        profile.append("(allow file-read* (subpath \"").append(workspace).append("\"))\n");
        profile.append("(allow file-read* (subpath \"").append(home).append("\"))\n");
        profile.append("(allow file-write* (subpath \"").append(workspace).append("\"))\n");
        profile.append("(allow file-write* (subpath \"").append(home).append("\"))\n");
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
