package io.ohmyluke.policy;

import java.time.Duration;
import java.util.Objects;

/** Immutable run limits. Zero disables the corresponding limit. */
public record PolicyLimits(
        long maxIterations,
        Duration maxElapsedTime,
        long maxNodeCalls,
        long maxToolCalls,
        long maxUsage) {
    public PolicyLimits {
        requireNonNegative(maxIterations, "maxIterations");
        maxElapsedTime = Objects.requireNonNull(maxElapsedTime, "maxElapsedTime");
        if (maxElapsedTime.isNegative()) {
            throw new IllegalArgumentException("maxElapsedTime must not be negative");
        }
        requireNonNegative(maxNodeCalls, "maxNodeCalls");
        requireNonNegative(maxToolCalls, "maxToolCalls");
        requireNonNegative(maxUsage, "maxUsage");
    }

    private static void requireNonNegative(long value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
    }
}
