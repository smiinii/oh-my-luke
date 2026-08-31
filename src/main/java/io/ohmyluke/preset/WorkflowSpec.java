package io.ohmyluke.preset;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Bounded static outer DAG. Only an EDIT's existing preset may retry internally. */
public record WorkflowSpec(int schemaVersion, String goal, String start, List<WorkflowStep> steps,
                           int maxSteps, long maxUsage, long maxElapsedMillis) {
    public WorkflowSpec {
        if (schemaVersion != 1) { throw new IllegalArgumentException("unsupported workflow schema version"); }
        goal = TaskSpec.text(goal, 8_192, "workflow goal");
        start = WorkflowStep.identifier(start);
        steps = List.copyOf(steps);
        if (steps.isEmpty() || steps.size() > 32 || maxSteps < 1 || maxSteps > 4_096
                || maxUsage < 0 || maxElapsedMillis < 1 || maxElapsedMillis > 3_600_000) {
            throw new IllegalArgumentException("invalid workflow size or limits");
        }
        Map<String, WorkflowStep> indexed = new LinkedHashMap<>();
        for (WorkflowStep step : steps) {
            if (indexed.putIfAbsent(step.id(), step) != null) { throw new IllegalArgumentException("duplicate workflow step"); }
            // One managed run has one operational budget. Do not silently ignore per-task budgets.
            if (step.task() != null && (step.task().maxUsage() != maxUsage || step.task().maxElapsedMillis() != maxElapsedMillis)) {
                throw new IllegalArgumentException("EDIT usage and elapsed limits must equal the shared workflow limits");
            }
        }
        if (!indexed.containsKey(start)) { throw new IllegalArgumentException("unknown workflow start"); }
        if (steps.stream().noneMatch(step -> step.onSuccess().equals("succeeded"))) {
            throw new IllegalArgumentException("workflow needs an objective path to success");
        }
        for (WorkflowStep step : steps) {
            for (String next : List.of(step.onSuccess(), step.onFailure())) {
                if (!indexed.containsKey(next) && !next.equals("succeeded") && !next.equals("stopped")) {
                    throw new IllegalArgumentException("unknown workflow route");
                }
            }
        }
        Set<String> reachable = new HashSet<>();
        visit(start, indexed, new HashMap<>(), reachable);
        if (reachable.size() != indexed.size()) { throw new IllegalArgumentException("unreachable workflow step"); }
    }

    public WorkflowSpec withRuntimeSelection(String model, String reasoning) {
        // Validate explicit choices even in an AI-free workflow.
        if (model != null) { TaskSpec.rejectSecrets(model); io.ohmyluke.ai.codex.CodexModelSelection.explicit(model); }
        if (reasoning != null) { io.ohmyluke.ai.codex.CodexReasoningEffort.valueOf(reasoning.toUpperCase(java.util.Locale.ROOT)); }
        return new WorkflowSpec(schemaVersion, goal, start, steps.stream().map(step -> step.task() == null ? step
                : WorkflowStep.edit(step.id(), step.task().withRuntimeSelection(model, reasoning), step.approvalBeforeApply(),
                        step.onSuccess(), step.onFailure())).toList(), maxSteps, maxUsage, maxElapsedMillis);
    }

    private static void visit(String id, Map<String, WorkflowStep> steps, Map<String, Integer> colors, Set<String> reachable) {
        if (!steps.containsKey(id)) { return; }
        if (colors.getOrDefault(id, 0) == 1) { throw new IllegalArgumentException("outer workflow cycles are not supported"); }
        if (colors.getOrDefault(id, 0) == 2) { return; }
        colors.put(id, 1);
        reachable.add(id);
        WorkflowStep step = steps.get(id);
        visit(step.onSuccess(), steps, colors, reachable);
        visit(step.onFailure(), steps, colors, reachable);
        colors.put(id, 2);
    }
}
