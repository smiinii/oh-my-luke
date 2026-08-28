package io.ohmyluke.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CompletionPolicyTest {
    private final CompletionPolicy policy = new CompletionPolicy(new CompletionEvaluator());

    @Test
    void returnsSuccessOnlyWhenTheConditionIsObjectivelySatisfied() {
        CompletionCondition condition = new CompletionCondition.FileExists("artifact.txt");

        PolicyDecision success = policy.evaluate(
                condition,
                new CompletionFacts(Map.of(), Set.of("artifact.txt"), 0, Set.of()));
        PolicyDecision pending = policy.evaluate(
                condition,
                new CompletionFacts(Map.of(), Set.of(), 0, Set.of()));

        assertEquals(PolicyOutcome.SUCCESS, success.outcome());
        assertEquals("completion.satisfied", success.reasonCode());
        assertFalse(success.resumable());
        assertEquals(PolicyOutcome.CONTINUE, pending.outcome());
        assertEquals("completion.pending", pending.reasonCode());
        assertTrue(pending.resumable());
    }

    @Test
    void decisionRequiresStableMachineReadableReason() {
        PolicyDecision decision = new PolicyDecision(
                PolicyOutcome.BLOCKED,
                "failure.repeated",
                "같은 실패가 3회 반복됨",
                true);

        assertEquals(PolicyOutcome.BLOCKED, decision.outcome());
        assertEquals("failure.repeated", decision.reasonCode());
        assertEquals("같은 실패가 3회 반복됨", decision.detail());
        assertTrue(decision.resumable());
    }

    @Test
    void compositeConditionsCannotBeEmpty() {
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new CompletionCondition.All(List.of()));
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new CompletionCondition.Any(List.of()));
    }

    @Test
    void decisionRejectsContradictoryResumeMetadata() {
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new PolicyDecision(PolicyOutcome.SUCCESS, "completion.satisfied", "done", true));
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new PolicyDecision(PolicyOutcome.CANCELLED, "run.cancelled", "cancelled", true));
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new PolicyDecision(PolicyOutcome.CONTINUE, "policy.continue", "continue", false));
    }
}
