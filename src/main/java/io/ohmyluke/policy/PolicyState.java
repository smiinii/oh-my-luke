package io.ohmyluke.policy;

import java.util.Objects;

/** Persistable counters and history required for deterministic stop decisions. */
public record PolicyState(
        long startedAtEpochMilli,
        long iterations,
        long nodeCalls,
        long toolCalls,
        long usage,
        FailureFingerprint lastFailure,
        int repeatedFailureCount,
        String lastStateFingerprint,
        int noProgressCount,
        PolicyDecision lastDecision) {
    public PolicyState {
        requireNonNegative(startedAtEpochMilli, "startedAtEpochMilli");
        requireNonNegative(iterations, "iterations");
        requireNonNegative(nodeCalls, "nodeCalls");
        requireNonNegative(toolCalls, "toolCalls");
        requireNonNegative(usage, "usage");
        requireNonNegative(repeatedFailureCount, "repeatedFailureCount");
        if (lastStateFingerprint != null && lastStateFingerprint.isBlank()) {
            throw new IllegalArgumentException("lastStateFingerprint must be null or non-blank");
        }
        requireNonNegative(noProgressCount, "noProgressCount");
        Objects.requireNonNull(lastDecision, "lastDecision");
    }

    public static PolicyState initial(long startedAtEpochMilli) {
        return new PolicyState(
                startedAtEpochMilli,
                0,
                0,
                0,
                0,
                null,
                0,
                null,
                0,
                PolicyDecision.continueExecution(
                        "policy.not-evaluated",
                        "policy has not evaluated a completed step"));
    }

    public PolicyState withCounters(
            long newIterations,
            long newNodeCalls,
            long newToolCalls,
            long newUsage) {
        return new PolicyState(
                startedAtEpochMilli,
                newIterations,
                newNodeCalls,
                newToolCalls,
                newUsage,
                lastFailure,
                repeatedFailureCount,
                lastStateFingerprint,
                noProgressCount,
                lastDecision);
    }

    public PolicyState withDecision(PolicyDecision decision) {
        return new PolicyState(
                startedAtEpochMilli,
                iterations,
                nodeCalls,
                toolCalls,
                usage,
                lastFailure,
                repeatedFailureCount,
                lastStateFingerprint,
                noProgressCount,
                decision);
    }

    static PolicyState observed(
            PolicyState previous,
            long iterations,
            long nodeCalls,
            long toolCalls,
            long usage,
            FailureFingerprint failure,
            int repeatedFailureCount,
            String stateFingerprint,
            int noProgressCount) {
        return new PolicyState(
                previous.startedAtEpochMilli,
                iterations,
                nodeCalls,
                toolCalls,
                usage,
                failure,
                repeatedFailureCount,
                stateFingerprint,
                noProgressCount,
                previous.lastDecision);
    }

    private static void requireNonNegative(long value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
    }
}
