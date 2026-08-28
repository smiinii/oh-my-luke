package io.ohmyluke.tool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** Prepared sandbox launcher and its OML-owned temporary profile. */
public final class SandboxLaunch implements AutoCloseable {
    private final List<String> command;
    private final Path temporaryProfile;

    public SandboxLaunch(List<String> command, Path temporaryProfile) {
        this.command = List.copyOf(Objects.requireNonNull(command, "command"));
        if (this.command.isEmpty()) {
            throw new IllegalArgumentException("sandbox command must not be empty");
        }
        this.temporaryProfile = temporaryProfile;
    }

    public static SandboxLaunch direct(List<String> command) {
        return new SandboxLaunch(command, null);
    }

    public List<String> command() {
        return command;
    }

    @Override
    public void close() {
        if (temporaryProfile == null) {
            return;
        }
        try {
            Files.deleteIfExists(temporaryProfile);
        } catch (IOException ignored) {
            // A stale profile contains paths only and grants no authority by itself.
        }
    }
}
