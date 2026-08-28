package io.ohmyluke.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ProgressTrackerTest {
    private final ProgressTracker tracker = new ProgressTracker();
    private final StagnationPolicy policy = new StagnationPolicy(3, 3);

    @Test
    void blocksAfterTheSameNormalizedFailureOccursThreeTimes() {
        FailureFingerprint first = FailureFingerprint.normalized(
                "VALIDATION", "TEST_FAILED", "inspect", "  Assertion   FAILED ");
        FailureFingerprint same = FailureFingerprint.normalized(
                "validation", "test_failed", "inspect", "assertion failed");
        PolicyState state = PolicyState.initial(0);

        state = tracker.observe(state, new ProgressObservation(1, 1, 0, 0, first, "state-a"));
        state = tracker.observe(state, new ProgressObservation(1, 1, 0, 0, same, "state-b"));
        state = tracker.observe(state, new ProgressObservation(1, 1, 0, 0, first, "state-c"));
        PolicyDecision decision = policy.evaluate(state);

        assertEquals(3, state.repeatedFailureCount());
        assertEquals(PolicyOutcome.BLOCKED, decision.outcome());
        assertEquals("failure.repeated", decision.reasonCode());
    }

    @Test
    void differentFailureResetsTheRepeatedFailureCount() {
        FailureFingerprint first = failure("compile");
        FailureFingerprint different = failure("test");
        PolicyState state = tracker.observe(
                PolicyState.initial(0),
                new ProgressObservation(1, 1, 0, 0, first, "state-a"));
        state = tracker.observe(state, new ProgressObservation(1, 1, 0, 0, first, "state-b"));

        state = tracker.observe(state, new ProgressObservation(1, 1, 0, 0, different, "state-c"));

        assertEquals(1, state.repeatedFailureCount());
        assertEquals(PolicyOutcome.CONTINUE, policy.evaluate(state).outcome());
    }

    @Test
    void blocksAfterThreeConsecutiveObservationsWithoutProgress() {
        PolicyState state = PolicyState.initial(0);
        state = tracker.observe(state, observation(null, "same"));
        state = tracker.observe(state, observation(null, "same"));
        state = tracker.observe(state, observation(null, "same"));
        state = tracker.observe(state, observation(null, "same"));

        PolicyDecision decision = policy.evaluate(state);

        assertEquals(3, state.noProgressCount());
        assertEquals(PolicyOutcome.BLOCKED, decision.outcome());
        assertEquals("progress.stalled", decision.reasonCode());
    }

    @Test
    void changedStateResetsNoProgressCount() {
        PolicyState state = PolicyState.initial(0);
        state = tracker.observe(state, observation(null, "same"));
        state = tracker.observe(state, observation(null, "same"));
        state = tracker.observe(state, observation(null, "same"));

        state = tracker.observe(state, observation(null, "changed"));

        assertEquals(0, state.noProgressCount());
        assertEquals(PolicyOutcome.CONTINUE, policy.evaluate(state).outcome());
    }

    @Test
    void observationsAccumulateEveryUsageCounter() {
        PolicyState state = tracker.observe(
                PolicyState.initial(0),
                new ProgressObservation(2, 3, 4, 5, null, "state"));

        assertEquals(2, state.iterations());
        assertEquals(3, state.nodeCalls());
        assertEquals(4, state.toolCalls());
        assertEquals(5, state.usage());
    }

    @Test
    void countersSaturateInsteadOfWrappingOrThrowing() {
        PolicyState state = PolicyState.initial(0).withCounters(Long.MAX_VALUE, 0, 0, 0);

        PolicyState observed = tracker.observe(state, observation(null, "state"));

        assertEquals(Long.MAX_VALUE, observed.iterations());
        assertEquals(
                "counter.capacity-reached",
                new PolicyEngine(java.time.Clock.systemUTC())
                        .evaluateOperational(PolicyConfiguration.unlimited(), observed)
                        .reasonCode());
    }

    @Test
    void constructorNormalizationPreservesNodeIdentityCase() {
        FailureFingerprint direct = new FailureFingerprint(" NODE ", " FAILED ", "Build", " Error  HERE ");
        FailureFingerprint normalized = FailureFingerprint.normalized("node", "failed", "Build", "error here");
        FailureFingerprint otherNode = FailureFingerprint.normalized("node", "failed", "build", "error here");

        assertEquals(normalized, direct);
        org.junit.jupiter.api.Assertions.assertNotEquals(normalized, otherNode);
    }

    private static ProgressObservation observation(FailureFingerprint failure, String state) {
        return new ProgressObservation(1, 1, 0, 0, failure, state);
    }

    private static FailureFingerprint failure(String code) {
        return FailureFingerprint.normalized("build", code, "work", "failed");
    }
}
