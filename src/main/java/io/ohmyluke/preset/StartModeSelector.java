package io.ohmyluke.preset;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Deterministic preset selection only: no AI, tool execution, or dynamic graph planning. */
public final class StartModeSelector {
    private StartModeSelector() {}

    public static StartPlan select(StartSpec spec, StartChoice choice) {
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(choice, "choice");
        RunSelection selection;
        if (choice == StartChoice.AUTO) {
            if (spec.workflow() != null) { selection = automatic(RunSelection.Mode.WORKFLOW, "auto-workflow-declared"); }
            else if (spec.task().approvalBeforeApply()) { selection = automatic(RunSelection.Mode.WORKFLOW, "auto-approval-required"); }
            else if (spec.task().maxAttempts() == 1) { selection = automatic(RunSelection.Mode.DIRECT, "auto-single-attempt"); }
            else { selection = automatic(RunSelection.Mode.LOOP, "auto-bounded-retry"); }
        } else {
            RunSelection.Mode mode = RunSelection.Mode.valueOf(choice.name());
            if (!spec.manualModes().contains(mode)) {
                throw new IllegalArgumentException("selected mode cannot preserve the declared workflow or approval requirement");
            }
            selection = new RunSelection(1, RunSelection.Strategy.MANUAL, mode, "manual-" + mode.name().toLowerCase(Locale.ROOT));
        }
        if (selection.mode() == RunSelection.Mode.WORKFLOW) {
            return new StartPlan(selection, null, spec.workflow() != null ? spec.workflow() : workflow(spec.task()));
        }
        return new StartPlan(selection, spec.task().toTask(ExecutionMode.valueOf(selection.mode().name())), null);
    }

    private static RunSelection automatic(RunSelection.Mode mode, String reason) {
        return new RunSelection(1, RunSelection.Strategy.AUTO, mode, reason);
    }

    private static WorkflowSpec workflow(StartTaskSpec input) {
        TaskSpec task = input.toTask(input.maxAttempts() == 1 ? ExecutionMode.DIRECT : ExecutionMode.LOOP);
        // Each retry has up to five preset nodes and one approval visit, followed by the result node.
        return new WorkflowSpec(1, input.goal(), "edit",
                List.of(WorkflowStep.edit("edit", task, input.approvalBeforeApply(), "succeeded", "stopped")),
                input.maxAttempts() * 6 + 2, input.maxUsage(), input.maxElapsedMillis());
    }
}
