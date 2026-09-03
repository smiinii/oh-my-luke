package io.ohmyluke.preset;

import java.util.Objects;

/** A resolved selection and precisely one effective contract; no execution has happened yet. */
public record StartPlan(RunSelection selection, TaskSpec task, WorkflowSpec workflow) {
    public StartPlan {
        Objects.requireNonNull(selection, "selection");
        if ((task == null) == (workflow == null)) {
            throw new IllegalArgumentException("start plan requires exactly one effective contract");
        }
        if (workflow != null ? selection.mode() != RunSelection.Mode.WORKFLOW
                : !selection.mode().name().equals(task.mode().name())) {
            throw new IllegalArgumentException("selection mode does not match the effective contract");
        }
    }
}
