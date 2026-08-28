package io.ohmyluke.policy;

import java.util.Objects;

/** One deterministic update to counters, failure history, and progress identity. */
public record ProgressObservation(
        long iterations,
        long nodeCalls,
        long toolCalls,
        long usage,
        FailureFingerprint failure,
        String stateFingerprint) {
    public ProgressObservation {
        requireNonNegative(iterations, "iterations");
        requireNonNegative(nodeCalls, "nodeCalls");
        requireNonNegative(toolCalls, "toolCalls");
        requireNonNegative(usage, "usage");
        Objects.requireNonNull(stateFingerprint, "stateFingerprint");
        if (stateFingerprint.isBlank()) {
            throw new IllegalArgumentException("stateFingerprint must not be blank");
        }
    }

    private static void requireNonNegative(long value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
    }
}
