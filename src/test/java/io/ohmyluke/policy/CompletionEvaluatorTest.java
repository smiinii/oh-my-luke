package io.ohmyluke.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CompletionEvaluatorTest {
    private final CompletionEvaluator evaluator = new CompletionEvaluator();

    @Test
    void allRequiresEveryObjectiveCondition() {
        CommandInvocation tests = new CommandInvocation("./gradlew", List.of("test"));
        CompletionFacts facts = new CompletionFacts(
                Map.of(tests, 0),
                Set.of("src/main/java/example/AuthService.java"),
                0,
                Set.of("auth-refresh"));
        CompletionCondition condition = new CompletionCondition.All(List.of(
                new CompletionCondition.CommandExitCode(tests, 0),
                new CompletionCondition.FileExists("src/main/java/example/AuthService.java"),
                new CompletionCondition.UnresolvedCriticalIssues(0),
                new CompletionCondition.RequirementSatisfied("auth-refresh")));

        CompletionResult result = evaluator.evaluate(condition, facts);

        assertTrue(result.satisfied());
        assertEquals(4, result.evidence().size());
        assertTrue(result.evidence().stream().allMatch(ConditionEvidence::satisfied));
    }

    @Test
    void allReportsEveryUnsatisfiedLeafInDeclarationOrder() {
        CommandInvocation tests = new CommandInvocation("./gradlew", List.of("test"));
        CompletionCondition condition = new CompletionCondition.All(List.of(
                new CompletionCondition.CommandExitCode(tests, 0),
                new CompletionCondition.FileExists("build/report.html"),
                new CompletionCondition.UnresolvedCriticalIssues(0)));
        CompletionFacts facts = new CompletionFacts(Map.of(tests, 1), Set.of(), 2, Set.of());

        CompletionResult result = evaluator.evaluate(condition, facts);

        assertFalse(result.satisfied());
        assertEquals(
                List.of(
                        "command-exit-code:./gradlew test",
                        "file-exists:build/report.html",
                        "unresolved-critical-issues:0"),
                result.evidence().stream().map(ConditionEvidence::conditionId).toList());
        assertTrue(result.evidence().stream().noneMatch(ConditionEvidence::satisfied));
    }

    @Test
    void anySucceedsWhenAtLeastOneChildSucceeds() {
        CompletionCondition condition = new CompletionCondition.Any(List.of(
                new CompletionCondition.FileExists("first.txt"),
                new CompletionCondition.FileExists("second.txt")));
        CompletionFacts facts = new CompletionFacts(
                Map.of(),
                Set.of("second.txt"),
                1,
                Set.of());

        CompletionResult result = evaluator.evaluate(condition, facts);

        assertTrue(result.satisfied());
        assertEquals(List.of(false, true), result.evidence().stream()
                .map(ConditionEvidence::satisfied)
                .toList());
    }

    @Test
    void sameFactsProduceTheSameEvidence() {
        CompletionCondition condition = new CompletionCondition.All(List.of(
                new CompletionCondition.FileExists("result.txt"),
                new CompletionCondition.UnresolvedCriticalIssues(0)));
        CompletionFacts facts = new CompletionFacts(Map.of(), Set.of("result.txt"), 0, Set.of());

        assertEquals(evaluator.evaluate(condition, facts), evaluator.evaluate(condition, facts));
    }
}
