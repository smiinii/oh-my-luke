package io.ohmyluke.ai.codex;

import java.util.Objects;

/** Safe installation and authentication summary from official Codex CLI commands. */
public record CodexRuntimeProbe(
        boolean installed,
        boolean authenticated,
        String version) {
    public CodexRuntimeProbe {
        version = Objects.requireNonNull(version, "version");
    }

    public static CodexRuntimeProbe unavailable() {
        return new CodexRuntimeProbe(false, false, "");
    }
}
