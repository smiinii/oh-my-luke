package io.ohmyluke.policy;

import java.util.Objects;

/** Converts objective completion evidence into success or continue decisions. */
public final class CompletionPolicy {
    private final CompletionEvaluator evaluator;

    public CompletionPolicy(CompletionEvaluator evaluator) {
        this.evaluator = Objects.requireNonNull(evaluator, "evaluator");
    }

    public PolicyDecision evaluate(CompletionCondition condition, CompletionFacts facts) {
        CompletionResult result = evaluator.evaluate(condition, facts);
        if (result.satisfied()) {
            return PolicyDecision.success(
                    "completion.satisfied",
                    "all required objective completion evidence is satisfied");
        }
        return PolicyDecision.continueExecution(
                "completion.pending",
                "the objective completion expression is not satisfied");
    }
}
