package io.ohmyluke.policy;

import java.time.Clock;
import java.util.Objects;

/** Combines completion, execution-limit, and stagnation decisions in a stable priority order. */
public final class PolicyEngine {
    private final CompletionPolicy completionPolicy;
    private final LimitPolicy limitPolicy;

    public PolicyEngine(Clock clock) {
        this.completionPolicy = new CompletionPolicy(new CompletionEvaluator());
        this.limitPolicy = new LimitPolicy(Objects.requireNonNull(clock, "clock"));
    }

    public PolicyDecision evaluateOperational(PolicyConfiguration configuration, PolicyState state) {
        Objects.requireNonNull(configuration, "configuration");
        Objects.requireNonNull(state, "state");
        PolicyDecision limit = limitPolicy.evaluate(configuration.limits(), state);
        if (limit.outcome() != PolicyOutcome.CONTINUE) {
            return limit;
        }
        PolicyDecision stagnation = new StagnationPolicy(
                        configuration.maxRepeatedFailures(),
                        configuration.maxNoProgress())
                .evaluate(state);
        if (stagnation.outcome() != PolicyOutcome.CONTINUE) {
            return stagnation;
        }
        return PolicyDecision.continueExecution(
                "policy.continue",
                "completion is pending and no operational stop condition was reached");
    }

    public PolicyDecision evaluateCompletion(
            CompletionCondition condition,
            CompletionFacts facts,
            PolicyConfiguration configuration,
            PolicyState state) {
        PolicyDecision completion = completionPolicy.evaluate(condition, facts);
        if (completion.outcome() == PolicyOutcome.SUCCESS) {
            return completion;
        }
        return evaluateOperational(configuration, state);
    }
}
