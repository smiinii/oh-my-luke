package io.ohmyluke.tool;

import io.ohmyluke.policy.ToolPermissionDecision;
import java.util.Objects;

/** Bounded and redacted process result. */
public record ProcessToolResult(
        ToolPermissionDecision permission,
        boolean executed,
        int exitCode,
        String standardOutput,
        String standardError,
        boolean timedOut,
        boolean outputTruncated,
        long elapsedMillis,
        String detail) {
    public ProcessToolResult {
        Objects.requireNonNull(permission, "permission");
        standardOutput = Objects.requireNonNull(standardOutput, "standardOutput");
        standardError = Objects.requireNonNull(standardError, "standardError");
        detail = requireText(detail, "detail");
        if (!executed && (exitCode != -1 || timedOut || !standardOutput.isEmpty() || !standardError.isEmpty())) {
            throw new IllegalArgumentException("a non-executed process must not report execution data");
        }
        if (elapsedMillis < 0) {
            throw new IllegalArgumentException("elapsedMillis must not be negative");
        }
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
