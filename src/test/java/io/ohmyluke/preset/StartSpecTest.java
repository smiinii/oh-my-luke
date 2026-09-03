package io.ohmyluke.preset;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class StartSpecTest {
    @Test void exactlyOneValidatedContractIsRequired() {
        StartTaskSpec task = task(3, false);
        WorkflowSpec workflow = workflow();
        assertThrows(IllegalArgumentException.class, () -> new StartSpec(1, null, null));
        assertThrows(IllegalArgumentException.class, () -> new StartSpec(1, task, workflow));
        assertThrows(IllegalArgumentException.class, () -> new StartSpec(2, task, null));
        assertThrows(IllegalArgumentException.class, () -> task(0, false));
        assertThrows(IllegalArgumentException.class, () -> task(21, false));
        assertThrows(IllegalArgumentException.class, () -> new StartTaskSpec("Make ready", "../outside.txt", 2,
                1_000, 45_000, 2, validation(), null, null, false));
    }

    @Test void strictJsonAcceptsNoExecutionModeAndRejectsUnknownOrAmbiguousFields() {
        StartSpec spec = new StartSpec(1, task(3, true), null);
        String json = PresetJson.encode(spec);
        assertFalse(json.contains("\"mode\""));
        assertEquals(spec, PresetJson.decode(json, StartSpec.class));
        assertEquals(new StartSpec(1, null, workflow()),
                PresetJson.decode(PresetJson.encode(new StartSpec(1, null, workflow())), StartSpec.class));
        for (String invalid : List.of(json.replace("\"maxAttempts\":3", "\"maxAttempts\":\"3\""),
                json.replace("\"maxAttempts\":3", "\"maxAttempts\":null"),
                json.replace("\"maxAttempts\":3", "\"maxAttempts\":3,\"mode\":\"LOOP\""),
                json.replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"schemaVersion\":1"),
                json + " {}")) {
            assertThrows(IllegalArgumentException.class, () -> PresetJson.decode(invalid, StartSpec.class));
        }
    }

    @Test void runtimeSelectionPreservesEveryOperationalConstraintAndNormalizesReasoning() {
        StartSpec spec = new StartSpec(1, task(7, true), null);
        StartSpec changed = spec.withRuntimeSelection("chosen-model", "HIGH");
        assertEquals("chosen-model", changed.task().model());
        assertEquals("high", changed.task().reasoning());
        assertEquals(spec.task().goal(), changed.task().goal());
        assertEquals(spec.task().file(), changed.task().file());
        assertEquals(spec.task().maxAttempts(), changed.task().maxAttempts());
        assertEquals(spec.task().maxUsage(), changed.task().maxUsage());
        assertEquals(spec.task().maxElapsedMillis(), changed.task().maxElapsedMillis());
        assertEquals(spec.task().maxRepeatedFailures(), changed.task().maxRepeatedFailures());
        assertEquals(spec.task().validation(), changed.task().validation());
        assertTrue(changed.task().approvalBeforeApply());
        assertEquals(changed, changed.withRuntimeSelection(null, null));
        StartSpec graph = new StartSpec(1, null, workflow());
        assertEquals(graph.workflow().steps().getFirst(), graph.withRuntimeSelection("model", "low").workflow().steps().getFirst());
        assertThrows(IllegalArgumentException.class, () -> spec.withRuntimeSelection("model", "unknown"));
    }

    @Test void directConversionOnlyLowersAttemptBudget() {
        StartTaskSpec input = task(20, false);
        TaskSpec direct = input.toTask(ExecutionMode.DIRECT);
        assertEquals(1, direct.maxAttempts());
        assertEquals(input.maxUsage(), direct.maxUsage());
        assertEquals(input.maxElapsedMillis(), direct.maxElapsedMillis());
        assertEquals(input.maxRepeatedFailures(), direct.maxRepeatedFailures());
        assertEquals(input.validation(), direct.validation());
        assertEquals(input.model(), direct.model());
        assertEquals(input.reasoning(), direct.reasoning());
        assertEquals(20, input.toTask(ExecutionMode.LOOP).maxAttempts());
        assertEquals(1, task(1, false).toTask(ExecutionMode.LOOP).maxAttempts());
    }

    @Test void selectionPreservesTheOperatorOwnedValidationCommand() {
        ValidationSpec fixed = new ValidationSpec(List.of("ready"), List.of(),
                new ValidationCommand("/usr/bin/true", List.of(), 0, 1_000));
        StartTaskSpec task = new StartTaskSpec("Make ready", "hello.txt", 3, 123, 12_000, 3,
                fixed, "model", "HIGH", false);
        for (StartChoice choice : StartChoice.values()) {
            StartPlan plan = StartModeSelector.select(new StartSpec(1, task, null), choice);
            TaskSpec selected = plan.task() != null ? plan.task() : plan.workflow().steps().getFirst().task();
            assertEquals(fixed, selected.validation());
            assertEquals(123, selected.maxUsage());
            assertEquals(12_000, selected.maxElapsedMillis());
            assertEquals(3, selected.maxRepeatedFailures());
            assertEquals("model", selected.model());
            assertEquals("high", selected.reasoning());
        }
    }

    @Test void availableManualModesCannotRemoveApprovalOrDeclaredGraph() {
        assertEquals(List.of(RunSelection.Mode.DIRECT, RunSelection.Mode.LOOP, RunSelection.Mode.WORKFLOW),
                new StartSpec(1, task(3, false), null).manualModes());
        assertEquals(List.of(RunSelection.Mode.WORKFLOW), new StartSpec(1, task(3, true), null).manualModes());
        assertEquals(List.of(RunSelection.Mode.WORKFLOW), new StartSpec(1, null, workflow()).manualModes());
    }

    static ValidationSpec validation() { return new ValidationSpec(List.of("ready"), List.of("wrong"), null); }

    static StartTaskSpec task(int attempts, boolean approval) {
        return new StartTaskSpec("Make the file ready", "hello.txt", attempts, 1_000, 45_000, 20,
                validation(), "configured-model", "MeDiUm", approval);
    }

    static WorkflowSpec workflow() {
        return new WorkflowSpec(1, "Check and make ready", "check", List.of(
                WorkflowStep.check("check", "hello.txt", validation(), "succeeded", "approval"),
                WorkflowStep.approval("approval", "Proceed to edit?", "edit"),
                WorkflowStep.edit("edit", task(3, false).toTask(ExecutionMode.LOOP), true, "succeeded", "stopped")),
                80, 1_000, 45_000);
    }
}
