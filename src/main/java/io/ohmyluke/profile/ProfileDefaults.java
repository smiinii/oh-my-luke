package io.ohmyluke.profile;

import io.ohmyluke.preset.StartSpec;
import io.ohmyluke.preset.TaskSpec;
import io.ohmyluke.preset.WorkflowSpec;
import io.ohmyluke.preset.WorkflowStep;

/** Apply defaults only to missing contract fields, before CLI overrides and checkpoint persistence. */
public final class ProfileDefaults {
    private ProfileDefaults() {}

    public static TaskSpec apply(TaskSpec task, ExecutionProfile profile) {
        return task.withRuntimeSelection(task.model() == null ? profile.model() : null, null);
    }

    public static WorkflowSpec apply(WorkflowSpec spec, ExecutionProfile profile) {
        return new WorkflowSpec(spec.schemaVersion(), spec.goal(), spec.start(), spec.steps().stream()
                .map(step -> step.task() == null ? step : WorkflowStep.edit(step.id(), apply(step.task(), profile),
                        step.approvalBeforeApply(), step.onSuccess(), step.onFailure())).toList(),
                spec.maxSteps(), spec.maxUsage(), spec.maxElapsedMillis());
    }

    public static StartSpec apply(StartSpec spec, ExecutionProfile profile) {
        return spec.task() != null
                ? spec.withRuntimeSelection(spec.task().model() == null ? profile.model() : null, null)
                : new StartSpec(spec.schemaVersion(), null, apply(spec.workflow(), profile));
    }
}
