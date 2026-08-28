package io.ohmyluke.policy;

import java.util.Objects;

/** Advances persisted counters and consecutive failure/no-progress history. */
public final class ProgressTracker {
    public PolicyState recordAttempt(
            PolicyState previous,
            long iterations,
            long nodeCalls) {
        Objects.requireNonNull(previous, "previous");
        if (iterations < 0 || nodeCalls < 0) {
            throw new IllegalArgumentException("attempt counters must not be negative");
        }
        return previous.withCounters(
                saturatedAdd(previous.iterations(), iterations),
                saturatedAdd(previous.nodeCalls(), nodeCalls),
                previous.toolCalls(),
                previous.usage());
    }

    public PolicyState observe(PolicyState previous, ProgressObservation observation) {
        Objects.requireNonNull(previous, "previous");
        Objects.requireNonNull(observation, "observation");

        FailureFingerprint failure = observation.failure();
        int repeatedFailures = failure == null
                ? 0
                : failure.equals(previous.lastFailure())
                        ? saturatedIncrement(previous.repeatedFailureCount())
                        : 1;
        int noProgress = observation.stateFingerprint().equals(previous.lastStateFingerprint())
                ? saturatedIncrement(previous.noProgressCount())
                : 0;

        return PolicyState.observed(
                previous,
                saturatedAdd(previous.iterations(), observation.iterations()),
                saturatedAdd(previous.nodeCalls(), observation.nodeCalls()),
                saturatedAdd(previous.toolCalls(), observation.toolCalls()),
                saturatedAdd(previous.usage(), observation.usage()),
                failure,
                repeatedFailures,
                observation.stateFingerprint(),
                noProgress);
    }

    private static long saturatedAdd(long current, long delta) {
        return delta > Long.MAX_VALUE - current ? Long.MAX_VALUE : current + delta;
    }

    private static int saturatedIncrement(int current) {
        return current == Integer.MAX_VALUE ? Integer.MAX_VALUE : current + 1;
    }
}
