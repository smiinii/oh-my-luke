package io.ohmyluke.tool;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Linux/WSL2 launcher that exposes system binaries read-only and the disposable workspace read-write. */
public final class LinuxBubblewrapSandbox implements ProcessSandbox {
    private final Path bubblewrap;

    public LinuxBubblewrapSandbox() {
        this.bubblewrap = locate();
    }

    LinuxBubblewrapSandbox(Path bubblewrap) {
        this.bubblewrap = bubblewrap;
    }

    @Override
    public boolean available() {
        return bubblewrap != null && Files.isExecutable(bubblewrap);
    }

    @Override
    public String unavailableReason() {
        return available() ? "" : "bubblewrap (bwrap) is unavailable";
    }

    @Override
    public SandboxLaunch prepare(ProcessSandboxSpec specification) {
        if (!available()) {
            throw new ProcessToolException(unavailableReason());
        }
        ArrayList<String> command = new ArrayList<>();
        command.add(bubblewrap.toString());
        command.addAll(List.of(
                "--die-with-parent",
                "--as-pid-1",
                "--new-session",
                "--unshare-pid",
                "--unshare-uts",
                "--unshare-ipc"));
        if (!specification.networkAllowed()) {
            command.add("--unshare-net");
        }
        for (String system : List.of("/usr", "/bin", "/sbin", "/lib", "/lib64", "/etc", "/opt")) {
            if (Files.exists(Path.of(system))) {
                command.add("--ro-bind");
                command.add(system);
                command.add(system);
            }
        }
        command.addAll(List.of("--proc", "/proc", "--dev", "/dev", "--tmpfs", "/tmp"));
        Path workspaceParent = specification.workspaceRoot().getParent();
        Path homeParent = specification.isolatedHome().getParent();
        if (workspaceParent != null && workspaceParent.equals(homeParent)) {
            bind(command, workspaceParent);
        } else {
            bind(command, specification.workspaceRoot());
            bind(command, specification.isolatedHome());
        }
        command.add("--chdir");
        command.add(specification.workingDirectory().toString());
        command.add("--");
        command.add(specification.executable().toString());
        command.addAll(specification.arguments());
        return SandboxLaunch.direct(command);
    }

    private static void bind(List<String> command, Path path) {
        command.add("--bind");
        command.add(path.toString());
        command.add(path.toString());
    }

    private static Path locate() {
        for (String candidate : List.of("/usr/bin/bwrap", "/bin/bwrap", "/usr/local/bin/bwrap")) {
            Path path = Path.of(candidate);
            if (Files.isExecutable(path)) {
                return path;
            }
        }
        return null;
    }
}
