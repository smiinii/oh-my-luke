package io.ohmyluke.policy;

import java.util.Objects;

/** Advances persisted counters and consecutive failure/no-progress history. */
public final class ProgressTracker {
    public PolicyState observe(PolicyState previous, ProgressObservation observation) {
        Objects.requireNonNull(previous, "previous");
        Objects.requireNonNull(observation, "observation");

        FailureFingerprint failure = observation.failure();
        int repeatedFailures = failure == null
                ? 0
                : failure.equals(previous.lastFailure())
                        ? Math.addExact(previous.repeatedFailureCount(), 1)
                        : 1;
        int noProgress = observation.stateFingerprint().equals(previous.lastStateFingerprint())
                ? Math.addExact(previous.noProgressCount(), 1)
                : 0;

        return PolicyState.observed(
                previous,
                Math.addExact(previous.iterations(), observation.iterations()),
                Math.addExact(previous.nodeCalls(), observation.nodeCalls()),
                Math.addExact(previous.toolCalls(), observation.toolCalls()),
                Math.addExact(previous.usage(), observation.usage()),
                failure,
                repeatedFailures,
                observation.stateFingerprint(),
                noProgress);
    }
}
