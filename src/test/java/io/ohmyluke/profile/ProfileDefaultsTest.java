package io.ohmyluke.profile;

import static org.junit.jupiter.api.Assertions.*;

import io.ohmyluke.preset.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProfileDefaultsTest {
    private final ExecutionProfile defaults = new ExecutionProfile("codex", "oml", "profile-model");

    @Test void onlyMissingTaskFieldsInheritAndRunFlagsWinWithoutMutatingTheInput() {
        TaskSpec task = task(null);
        assertEquals("profile-model", ProfileDefaults.apply(task, defaults).model());
        assertNull(task.model());
        assertEquals("task-model", ProfileDefaults.apply(task("task-model"), defaults).model());
        assertEquals("run-model", ProfileDefaults.apply(task("task-model"), defaults)
                .withRuntimeSelection("run-model", "high").model());
        assertEquals("low", ProfileDefaults.apply(task, defaults).reasoning());
        assertNull(ProfileDefaults.apply(task, ExecutionProfile.defaults()).model());
    }

    @Test void mixedWorkflowModelsAreResolvedPerStepAndThenOverriddenTogether() {
        var workflow = new WorkflowSpec(1, "Check two files", "one", List.of(
                WorkflowStep.edit("one", task(null), false, "two", "stopped"),
                WorkflowStep.edit("two", task("step-model"), false, "succeeded", "stopped")), 20, 0, 60_000);
        var resolved = ProfileDefaults.apply(workflow, defaults);
        assertEquals("profile-model", resolved.steps().get(0).task().model());
        assertEquals("step-model", resolved.steps().get(1).task().model());
        assertNull(workflow.steps().get(0).task().model());
        assertTrue(resolved.withRuntimeSelection("run-model", null).steps().stream()
                .allMatch(step -> step.task().model().equals("run-model")));
    }

    @Test void startTaskAndWorkflowUseTheSameDefaultResolution() {
        var task = new StartTaskSpec("Check the file", "hello.txt", 2, 0, 60_000, 1,
                new ValidationSpec(List.of("ready"), List.of(), null), null, null, false);
        assertEquals("profile-model", ProfileDefaults.apply(new StartSpec(1, task, null), defaults).task().model());
        var explicit = task.withRuntimeSelection("explicit", null);
        assertEquals("explicit", ProfileDefaults.apply(new StartSpec(1, explicit, null), defaults).task().model());
    }

    private TaskSpec task(String model) {
        return new TaskSpec(1, "Check the file", "hello.txt", ExecutionMode.DIRECT, 1, 0, 60_000, 1,
                new ValidationSpec(List.of("ready"), List.of(), null), model, "low");
    }
}
