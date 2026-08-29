package io.ohmyluke.tool;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** Exact executable boundary applied to an isolated project copy. */
public record ProcessSandboxSpec(
        Path executable,
        List<String> arguments,
        Path workspaceRoot,
        Path workingDirectory,
        Path isolatedHome,
        boolean networkAllowed) {
    public ProcessSandboxSpec {
        Objects.requireNonNull(executable, "executable");
        arguments = List.copyOf(Objects.requireNonNull(arguments, "arguments"));
        Objects.requireNonNull(workspaceRoot, "workspaceRoot");
        Objects.requireNonNull(workingDirectory, "workingDirectory");
        Objects.requireNonNull(isolatedHome, "isolatedHome");
    }
}
