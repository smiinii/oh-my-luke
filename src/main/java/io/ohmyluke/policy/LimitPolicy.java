package io.ohmyluke.policy;

import java.time.Clock;
import java.util.Objects;

/** Evaluates deterministic count and elapsed-time limits in a fixed priority order. */
public final class LimitPolicy {
    private final Clock clock;

    public LimitPolicy(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public PolicyDecision evaluate(PolicyLimits limits, PolicyState state) {
        Objects.requireNonNull(limits, "limits");
        Objects.requireNonNull(state, "state");
        PolicyDecision decision = reached(
                limits.maxIterations(), state.iterations(), "limit.iterations", "iteration");
        if (decision != null) {
            return decision;
        }
        decision = reached(limits.maxNodeCalls(), state.nodeCalls(), "limit.node-calls", "node call");
        if (decision != null) {
            return decision;
        }
        decision = reached(limits.maxToolCalls(), state.toolCalls(), "limit.tool-calls", "tool call");
        if (decision != null) {
            return decision;
        }
        decision = reached(limits.maxUsage(), state.usage(), "limit.usage", "usage");
        if (decision != null) {
            return decision;
        }
        if (!limits.maxElapsedTime().isZero()) {
            long elapsed = Math.max(0, clock.millis() - state.startedAtEpochMilli());
            if (elapsed >= limits.maxElapsedTime().toMillis()) {
                return limit(
                        "limit.elapsed-time",
                        "elapsed time reached " + limits.maxElapsedTime().toMillis() + "ms");
            }
        }
        return PolicyDecision.continueExecution(
                "limit.within-bounds",
                "all configured execution limits remain available");
    }

    private static PolicyDecision reached(
            long maximum,
            long actual,
            String code,
            String label) {
        if (maximum == 0 || actual < maximum) {
            return null;
        }
        return limit(code, label + " count reached " + actual + "/" + maximum);
    }

    private static PolicyDecision limit(String code, String detail) {
        return new PolicyDecision(PolicyOutcome.LIMIT_REACHED, code, detail, false);
    }
}
