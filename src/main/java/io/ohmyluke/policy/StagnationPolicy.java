package io.ohmyluke.policy;

import java.util.Objects;

/** Stops deterministic repetition before a loop wastes more work. */
public final class StagnationPolicy {
    private final int maxRepeatedFailures;
    private final int maxNoProgress;

    public StagnationPolicy(int maxRepeatedFailures, int maxNoProgress) {
        if (maxRepeatedFailures < 0 || maxNoProgress < 0) {
            throw new IllegalArgumentException("stagnation limits must not be negative");
        }
        this.maxRepeatedFailures = maxRepeatedFailures;
        this.maxNoProgress = maxNoProgress;
    }

    public PolicyDecision evaluate(PolicyState state) {
        Objects.requireNonNull(state, "state");
        if (maxRepeatedFailures > 0 && state.repeatedFailureCount() >= maxRepeatedFailures) {
            return new PolicyDecision(
                    PolicyOutcome.BLOCKED,
                    "failure.repeated",
                    "same normalized failure repeated " + state.repeatedFailureCount() + " times",
                    true);
        }
        if (maxNoProgress > 0 && state.noProgressCount() >= maxNoProgress) {
            return new PolicyDecision(
                    PolicyOutcome.BLOCKED,
                    "progress.stalled",
                    "state fingerprint did not change for " + state.noProgressCount() + " observations",
                    true);
        }
        return PolicyDecision.continueExecution(
                "progress.detected",
                "no repeated failure or no-progress threshold was reached");
    }
}
