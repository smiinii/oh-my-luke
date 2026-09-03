package io.ohmyluke.preset;

import static org.junit.jupiter.api.Assertions.*;
import static io.ohmyluke.preset.StartSpecTest.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class StartModeSelectorTest {
    @Test void automaticSelectionIsDeterministicForEveryRule() {
        List<StartSpec> inputs = List.of(new StartSpec(1, task(1, false), null),
                new StartSpec(1, task(2, false), null), new StartSpec(1, task(1, true), null),
                new StartSpec(1, null, workflow()));
        List<RunSelection.Mode> expected = List.of(RunSelection.Mode.DIRECT, RunSelection.Mode.LOOP,
                RunSelection.Mode.WORKFLOW, RunSelection.Mode.WORKFLOW);
        List<String> reasons = List.of("auto-single-attempt", "auto-bounded-retry", "auto-approval-required", "auto-workflow-declared");
        for (int index = 0; index < inputs.size(); index++) {
            StartPlan plan = StartModeSelector.select(inputs.get(index), StartChoice.AUTO);
            assertEquals(new RunSelection(1, RunSelection.Strategy.AUTO, expected.get(index), reasons.get(index)), plan.selection());
            for (int repetition = 0; repetition < 10; repetition++) {
                assertEquals(plan, StartModeSelector.select(inputs.get(index), StartChoice.AUTO));
            }
        }
    }

    @Test void manualSelectionOverridesOnlyTheChosenStructure() {
        StartSpec input = new StartSpec(1, task(5, false), null);
        for (StartChoice choice : List.of(StartChoice.DIRECT, StartChoice.LOOP, StartChoice.WORKFLOW)) {
            StartPlan plan = StartModeSelector.select(input, choice);
            assertEquals(RunSelection.Strategy.MANUAL, plan.selection().strategy());
            assertEquals(choice.name(), plan.selection().mode().name());
            assertEquals("manual-" + choice.name().toLowerCase(java.util.Locale.ROOT), plan.selection().reasonCode());
            assertEquals(plan, StartModeSelector.select(input, choice));
        }
        assertEquals(1, StartModeSelector.select(input, StartChoice.DIRECT).task().maxAttempts());
        assertEquals(5, StartModeSelector.select(input, StartChoice.LOOP).task().maxAttempts());
        assertEquals(1, StartModeSelector.select(new StartSpec(1, task(1, false), null), StartChoice.LOOP).task().maxAttempts());
    }

    @Test void declaredWorkflowIsNeverFlattenedOrChanged() {
        WorkflowSpec graph = workflow();
        StartSpec input = new StartSpec(1, null, graph);
        assertSame(graph, StartModeSelector.select(input, StartChoice.AUTO).workflow());
        assertSame(graph, StartModeSelector.select(input, StartChoice.WORKFLOW).workflow());
        assertThrows(IllegalArgumentException.class, () -> StartModeSelector.select(input, StartChoice.DIRECT));
        assertThrows(IllegalArgumentException.class, () -> StartModeSelector.select(input, StartChoice.LOOP));
    }

    @Test void approvalRequirementAlwaysKeepsItsGateAndOriginalBudget() {
        StartTaskSpec input = task(20, true);
        StartSpec spec = new StartSpec(1, input, null);
        assertThrows(IllegalArgumentException.class, () -> StartModeSelector.select(spec, StartChoice.DIRECT));
        assertThrows(IllegalArgumentException.class, () -> StartModeSelector.select(spec, StartChoice.LOOP));
        for (StartChoice choice : List.of(StartChoice.AUTO, StartChoice.WORKFLOW)) {
            WorkflowSpec graph = StartModeSelector.select(spec, choice).workflow();
            assertEquals(1, graph.steps().size());
            WorkflowStep edit = graph.steps().getFirst();
            assertEquals(WorkflowStep.Type.EDIT, edit.type());
            assertTrue(edit.approvalBeforeApply());
            assertEquals(input.toTask(ExecutionMode.LOOP), edit.task());
            assertEquals(input.maxUsage(), graph.maxUsage());
            assertEquals(input.maxElapsedMillis(), graph.maxElapsedMillis());
            assertTrue(graph.maxSteps() >= input.maxAttempts() * 6 + 1);
        }
    }

    @Test void manualWorkflowUsesExistingPresetInsideOneStaticEdit() {
        StartSpec input = new StartSpec(1, task(1, false), null);
        WorkflowSpec graph = StartModeSelector.select(input, StartChoice.WORKFLOW).workflow();
        WorkflowStep edit = graph.steps().getFirst();
        assertEquals(graph.start(), edit.id());
        assertEquals("succeeded", edit.onSuccess());
        assertEquals("stopped", edit.onFailure());
        assertFalse(edit.approvalBeforeApply());
        assertEquals(input.task().toTask(ExecutionMode.DIRECT), edit.task());
    }

    @Test void planAndPersistedSelectionRejectInconsistentOrUnsafeMetadata() {
        RunSelection direct = new RunSelection(1, RunSelection.Strategy.MANUAL, RunSelection.Mode.DIRECT, "manual-direct");
        assertEquals(direct, PresetJson.decode(PresetJson.encode(direct), RunSelection.class));
        assertThrows(IllegalArgumentException.class, () -> new RunSelection(2, direct.strategy(), direct.mode(), direct.reasonCode()));
        assertThrows(IllegalArgumentException.class, () -> new RunSelection(1, direct.strategy(), direct.mode(), "private goal content"));
        assertThrows(IllegalArgumentException.class, () -> new RunSelection(1, direct.strategy(), direct.mode(), "auto-single-attempt"));
        assertThrows(IllegalArgumentException.class, () -> new StartPlan(direct, null, null));
        assertThrows(IllegalArgumentException.class, () -> new StartPlan(direct, task(1, false).toTask(ExecutionMode.DIRECT), workflow()));
        assertThrows(IllegalArgumentException.class, () -> new StartPlan(direct, task(3, false).toTask(ExecutionMode.LOOP), null));
        assertThrows(IllegalArgumentException.class, () -> new StartPlan(direct, null, workflow()));
    }
}
