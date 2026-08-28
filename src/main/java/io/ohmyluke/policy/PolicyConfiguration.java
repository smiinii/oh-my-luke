package io.ohmyluke.policy;

import java.time.Duration;

/** Persisted, operator-owned limits used for every decision in one run. */
public record PolicyConfiguration(
        long maxIterations,
        long maxElapsedMillis,
        long maxNodeCalls,
        long maxToolCalls,
        long maxUsage,
        int maxRepeatedFailures,
        int maxNoProgress) {
    public PolicyConfiguration {
        requireNonNegative(maxIterations, "maxIterations");
        requireNonNegative(maxElapsedMillis, "maxElapsedMillis");
        requireNonNegative(maxNodeCalls, "maxNodeCalls");
        requireNonNegative(maxToolCalls, "maxToolCalls");
        requireNonNegative(maxUsage, "maxUsage");
        requireNonNegative(maxRepeatedFailures, "maxRepeatedFailures");
        requireNonNegative(maxNoProgress, "maxNoProgress");
    }

    public static PolicyConfiguration unlimited() {
        return new PolicyConfiguration(0, 0, 0, 0, 0, 0, 0);
    }

    public PolicyLimits limits() {
        return new PolicyLimits(
                maxIterations,
                Duration.ofMillis(maxElapsedMillis),
                maxNodeCalls,
                maxToolCalls,
                maxUsage);
    }

    private static void requireNonNegative(long value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
    }
}
