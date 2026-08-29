package io.ohmyluke.ai;

import java.util.Objects;

/** Structured AI result with generic usage units that policy limits can count. */
public record AiRuntimeResult(
        AiRuntimeStatus status,
        String output,
        AiRuntimeFailure failure,
        long usage) {
    public AiRuntimeResult {
        Objects.requireNonNull(status, "status");
        output = Objects.requireNonNull(output, "output");
        if (usage < 0) {
            throw new IllegalArgumentException("usage must not be negative");
        }
        if (status == AiRuntimeStatus.SUCCESS && failure != null) {
            throw new IllegalArgumentException("successful result must not contain a failure");
        }
        if (status == AiRuntimeStatus.FAILURE && failure == null) {
            throw new IllegalArgumentException("failed result must contain a failure");
        }
        if (status == AiRuntimeStatus.FAILURE && !output.isEmpty()) {
            throw new IllegalArgumentException("failed result must not contain output");
        }
    }

    public static AiRuntimeResult success(String output, long usage) {
        return new AiRuntimeResult(AiRuntimeStatus.SUCCESS, output, null, usage);
    }

    public static AiRuntimeResult failure(String code, String cause, long usage) {
        return new AiRuntimeResult(
                AiRuntimeStatus.FAILURE,
                "",
                new AiRuntimeFailure(code, cause),
                usage);
    }
}
