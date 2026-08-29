package io.ohmyluke.ai.codex;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

/** Immutable configuration for the official Codex CLI adapter. */
public record CodexCliConfiguration(
        String executable,
        Path projectRoot,
        CodexModelSelection modelSelection,
        CodexReasoningSelection reasoningSelection,
        Duration timeout,
        int maxInputBytes,
        int maxOutputBytes) {
    private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(5);
    private static final int DEFAULT_MAX_INPUT_BYTES = 1024 * 1024;
    private static final int DEFAULT_MAX_OUTPUT_BYTES = 4 * 1024 * 1024;
    private static final int MAX_INPUT_BYTES = 16 * 1024 * 1024;
    private static final int MAX_OUTPUT_BYTES = 64 * 1024 * 1024;

    public CodexCliConfiguration {
        executable = requireText(executable, "executable");
        projectRoot = normalizeProjectRoot(projectRoot);
        modelSelection = Objects.requireNonNull(modelSelection, "modelSelection");
        reasoningSelection = Objects.requireNonNull(reasoningSelection, "reasoningSelection");
        timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        if (maxInputBytes < 1 || maxInputBytes > MAX_INPUT_BYTES) {
            throw new IllegalArgumentException("maxInputBytes must be between 1 and 16 MiB");
        }
        if (maxOutputBytes < 1 || maxOutputBytes > MAX_OUTPUT_BYTES) {
            throw new IllegalArgumentException("maxOutputBytes must be between 1 and 64 MiB");
        }
    }

    public static CodexCliConfiguration defaults(Path projectRoot) {
        return new CodexCliConfiguration(
                "codex",
                projectRoot,
                CodexModelSelection.inherit(),
                CodexReasoningSelection.inherit(),
                DEFAULT_TIMEOUT,
                DEFAULT_MAX_INPUT_BYTES,
                DEFAULT_MAX_OUTPUT_BYTES);
    }

    public static CodexCliConfiguration forExecutable(Path projectRoot, Path executable) {
        Objects.requireNonNull(executable, "executable");
        return new CodexCliConfiguration(
                executable.toAbsolutePath().normalize().toString(),
                projectRoot,
                CodexModelSelection.inherit(),
                CodexReasoningSelection.inherit(),
                DEFAULT_TIMEOUT,
                DEFAULT_MAX_INPUT_BYTES,
                DEFAULT_MAX_OUTPUT_BYTES);
    }

    public CodexCliConfiguration withModel(String model) {
        return copy(CodexModelSelection.explicit(model), reasoningSelection, timeout, maxInputBytes, maxOutputBytes);
    }

    public CodexCliConfiguration withReasoning(CodexReasoningEffort effort) {
        return copy(modelSelection, CodexReasoningSelection.explicit(effort), timeout, maxInputBytes, maxOutputBytes);
    }

    public CodexCliConfiguration withTimeout(Duration value) {
        return copy(modelSelection, reasoningSelection, value, maxInputBytes, maxOutputBytes);
    }

    public CodexCliConfiguration withMaxInputBytes(int value) {
        return copy(modelSelection, reasoningSelection, timeout, value, maxOutputBytes);
    }

    public CodexCliConfiguration withMaxOutputBytes(int value) {
        return copy(modelSelection, reasoningSelection, timeout, maxInputBytes, value);
    }

    private CodexCliConfiguration copy(
            CodexModelSelection model,
            CodexReasoningSelection reasoning,
            Duration newTimeout,
            int inputLimit,
            int outputLimit) {
        return new CodexCliConfiguration(
                executable,
                projectRoot,
                model,
                reasoning,
                newTimeout,
                inputLimit,
                outputLimit);
    }

    private static Path normalizeProjectRoot(Path value) {
        Objects.requireNonNull(value, "projectRoot");
        try {
            Path real = value.toRealPath();
            if (!Files.isDirectory(real)) {
                throw new IllegalArgumentException("projectRoot must be an existing directory");
            }
            return real;
        } catch (IOException error) {
            throw new IllegalArgumentException("projectRoot must exist and resolve safely", error);
        }
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(name + " must be nonblank and contain no control characters");
        }
        return value;
    }
}
