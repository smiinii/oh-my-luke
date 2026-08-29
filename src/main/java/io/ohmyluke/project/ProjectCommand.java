package io.ohmyluke.project;

import java.util.List;
import java.util.Objects;

public record ProjectCommand(
        Purpose purpose,
        ProjectBuildSystem buildSystem,
        List<String> arguments,
        String reason) {
    public enum Purpose {
        BUILD,
        TEST
    }

    public ProjectCommand {
        Objects.requireNonNull(purpose, "purpose");
        Objects.requireNonNull(buildSystem, "buildSystem");
        arguments = List.copyOf(Objects.requireNonNull(arguments, "arguments"));
        if (arguments.isEmpty() || arguments.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException("arguments must contain non-blank values");
        }
        reason = Objects.requireNonNull(reason, "reason");
        if (reason.isBlank()) {
            throw new IllegalArgumentException("reason must not be blank");
        }
    }
}
