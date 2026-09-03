package io.ohmyluke.preset;

import java.util.List;

/** Exactly one operator-owned single-file task or existing static workflow declaration. */
public record StartSpec(int schemaVersion, StartTaskSpec task, WorkflowSpec workflow) {
    public StartSpec {
        if (schemaVersion != 1) { throw new IllegalArgumentException("unsupported start schema version"); }
        if ((task == null) == (workflow == null)) {
            throw new IllegalArgumentException("start requires exactly one task or workflow");
        }
    }

    public StartSpec withRuntimeSelection(String model, String reasoning) {
        return task != null ? new StartSpec(schemaVersion, task.withRuntimeSelection(model, reasoning), null)
                : new StartSpec(schemaVersion, null, workflow.withRuntimeSelection(model, reasoning));
    }

    public List<RunSelection.Mode> manualModes() {
        return workflow != null || task.approvalBeforeApply() ? List.of(RunSelection.Mode.WORKFLOW)
                : List.of(RunSelection.Mode.DIRECT, RunSelection.Mode.LOOP, RunSelection.Mode.WORKFLOW);
    }
}
