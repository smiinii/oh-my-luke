package io.ohmyluke.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class LimitPolicyTest {
    private static final Instant STARTED = Instant.parse("2026-08-28T00:00:00Z");

    @Test
    void distinguishesEveryConfiguredLimitFromSuccess() {
        PolicyLimits limits = new PolicyLimits(3, Duration.ofMinutes(5), 4, 2, 100);
        Clock clock = Clock.fixed(STARTED.plus(Duration.ofMinutes(6)), ZoneOffset.UTC);
        LimitPolicy policy = new LimitPolicy(clock);

        assertLimit(policy, limits, state(3, 0, 0, 0), "limit.iterations");
        assertLimit(policy, limits, state(0, 4, 0, 0), "limit.node-calls");
        assertLimit(policy, limits, state(0, 0, 2, 0), "limit.tool-calls");
        assertLimit(policy, limits, state(0, 0, 0, 100), "limit.usage");
        assertLimit(policy, limits, state(0, 0, 0, 0), "limit.elapsed-time");
    }

    @Test
    void zeroLimitsAreDisabledAndStateCanContinue() {
        PolicyLimits unlimited = new PolicyLimits(0, Duration.ZERO, 0, 0, 0);
        LimitPolicy policy = new LimitPolicy(Clock.fixed(
                STARTED.plus(Duration.ofDays(1)),
                ZoneOffset.UTC));

        PolicyDecision decision = policy.evaluate(
                unlimited,
                state(1_000, 1_000, 1_000, 1_000));

        assertEquals(PolicyOutcome.CONTINUE, decision.outcome());
        assertEquals("limit.within-bounds", decision.reasonCode());
    }

    @Test
    void elapsedTimeUsesInjectedClock() {
        PolicyLimits limits = new PolicyLimits(0, Duration.ofSeconds(30), 0, 0, 0);
        LimitPolicy before = new LimitPolicy(Clock.fixed(STARTED.plusSeconds(29), ZoneOffset.UTC));
        LimitPolicy atLimit = new LimitPolicy(Clock.fixed(STARTED.plusSeconds(30), ZoneOffset.UTC));

        assertEquals(PolicyOutcome.CONTINUE, before.evaluate(limits, state(0, 0, 0, 0)).outcome());
        assertEquals(PolicyOutcome.LIMIT_REACHED, atLimit.evaluate(limits, state(0, 0, 0, 0)).outcome());
    }

    private static void assertLimit(
            LimitPolicy policy,
            PolicyLimits limits,
            PolicyState state,
            String reason) {
        PolicyDecision decision = policy.evaluate(limits, state);

        assertEquals(PolicyOutcome.LIMIT_REACHED, decision.outcome());
        assertEquals(reason, decision.reasonCode());
    }

    private static PolicyState state(
            long iterations,
            long nodeCalls,
            long toolCalls,
            long usage) {
        return PolicyState.initial(STARTED.toEpochMilli()).withCounters(
                iterations,
                nodeCalls,
                toolCalls,
                usage);
    }
}
