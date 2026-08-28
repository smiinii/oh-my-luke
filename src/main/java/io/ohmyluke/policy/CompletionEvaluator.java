package io.ohmyluke.policy;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Evaluates completion expressions without file, process, network, or AI access. */
public final class CompletionEvaluator {
    public CompletionResult evaluate(CompletionCondition condition, CompletionFacts facts) {
        Objects.requireNonNull(condition, "condition");
        Objects.requireNonNull(facts, "facts");
        return evaluateNode(condition, facts);
    }

    private CompletionResult evaluateNode(CompletionCondition condition, CompletionFacts facts) {
        if (condition instanceof CompletionCondition.All all) {
            return composite(all.conditions(), facts, true);
        }
        if (condition instanceof CompletionCondition.Any any) {
            return composite(any.conditions(), facts, false);
        }
        ConditionEvidence evidence = leaf(condition, facts);
        return new CompletionResult(evidence.satisfied(), List.of(evidence));
    }

    private CompletionResult composite(
            List<CompletionCondition> conditions,
            CompletionFacts facts,
            boolean requireAll) {
        List<ConditionEvidence> evidence = new ArrayList<>();
        boolean satisfied = requireAll;
        for (CompletionCondition child : conditions) {
            CompletionResult childResult = evaluateNode(child, facts);
            evidence.addAll(childResult.evidence());
            satisfied = requireAll
                    ? satisfied && childResult.satisfied()
                    : satisfied || childResult.satisfied();
        }
        return new CompletionResult(satisfied, evidence);
    }

    private ConditionEvidence leaf(CompletionCondition condition, CompletionFacts facts) {
        if (condition instanceof CompletionCondition.CommandExitCode command) {
            Integer actual = facts.commandExitCodes().get(command.command());
            return new ConditionEvidence(
                    "command-exit-code:" + command.command().display(),
                    actual != null && actual == command.expected(),
                    Integer.toString(command.expected()),
                    actual == null ? "missing" : actual.toString());
        }
        if (condition instanceof CompletionCondition.FileExists file) {
            boolean present = facts.existingFiles().contains(file.path());
            return new ConditionEvidence(
                    "file-exists:" + file.path(),
                    present,
                    "present",
                    present ? "present" : "missing");
        }
        if (condition instanceof CompletionCondition.UnresolvedCriticalIssues issues) {
            int actual = facts.unresolvedCriticalIssues();
            return new ConditionEvidence(
                    "unresolved-critical-issues:" + issues.expected(),
                    actual == issues.expected(),
                    Integer.toString(issues.expected()),
                    Integer.toString(actual));
        }
        CompletionCondition.RequirementSatisfied requirement =
                (CompletionCondition.RequirementSatisfied) condition;
        boolean satisfied = facts.satisfiedRequirements().contains(requirement.requirement());
        return new ConditionEvidence(
                "requirement-satisfied:" + requirement.requirement(),
                satisfied,
                "satisfied",
                satisfied ? "satisfied" : "pending");
    }
}
