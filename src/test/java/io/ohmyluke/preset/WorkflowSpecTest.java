package io.ohmyluke.preset;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class WorkflowSpecTest {
    @Test void strictRoundTripPreservesFixedRoutesAndOverridesOnlyRuntimeSelection() {
        WorkflowSpec spec = spec(List.of(check("check", "succeeded", "edit"), edit("edit", "succeeded", "stopped")));
        assertEquals(spec, PresetJson.decode(PresetJson.encode(spec), WorkflowSpec.class));
        WorkflowSpec selected = spec.withRuntimeSelection("chosen-model", "low");
        assertEquals("chosen-model", selected.steps().get(1).task().model());
        assertEquals(spec.steps().getFirst(), selected.steps().getFirst());
        assertEquals(spec.maxSteps(), selected.maxSteps());
    }

    @Test void invalidRoutesDuplicatesCyclesAndUnverifiedSuccessAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> spec(List.of(check("check", "succeeded", "missing"))));
        assertThrows(IllegalArgumentException.class, () -> spec(List.of(check("check", "succeeded", "stopped"), check("check", "succeeded", "stopped"))));
        assertThrows(IllegalArgumentException.class, () -> spec(List.of(check("check", "check", "stopped"))));
        assertThrows(IllegalArgumentException.class, () -> spec(List.of(check("check", "stopped", "succeeded"))));
        assertThrows(IllegalArgumentException.class, () -> spec(List.of(check("check", "stopped", "stopped"))));
        assertThrows(IllegalArgumentException.class, () -> spec(List.of(WorkflowStep.approval("check", "Continue?", "succeeded"))));
        assertThrows(IllegalArgumentException.class, () -> spec(List.of(check("check", "succeeded", "stopped"), check("unused", "succeeded", "stopped"))));
        assertThrows(IllegalArgumentException.class, () -> WorkflowStep.check("bad.id", "hello.txt", validation(), "succeeded", "stopped"));
    }

    @Test void protectedFilesAndAmbiguousFieldsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> WorkflowStep.check("check", ".oml/state.json", validation(), "succeeded", "stopped"));
        assertThrows(IllegalArgumentException.class, () -> PresetJson.decode("{\"schemaVersion\":1,\"schemaVersion\":1}", WorkflowSpec.class));
        WorkflowSpec valid = spec(List.of(check("check", "succeeded", "stopped")));
        String json = PresetJson.encode(valid).replace("\"maxSteps\":100", "\"maxSteps\":100,\"unsafe\":true");
        assertThrows(IllegalArgumentException.class, () -> PresetJson.decode(json, WorkflowSpec.class));
    }

    static WorkflowSpec spec(List<WorkflowStep> steps) { return new WorkflowSpec(1, "Make ready", "check", steps, 100, 0, 60_000); }
    static ValidationSpec validation() { return new ValidationSpec(List.of("ready"), List.of(), null); }
    static WorkflowStep check(String id, String yes, String no) { return WorkflowStep.check(id, "hello.txt", validation(), yes, no); }
    static WorkflowStep edit(String id, String yes, String no) { return WorkflowStep.edit(id, task(), false, yes, no); }
    static TaskSpec task() { return new TaskSpec(1, "Make ready", "hello.txt", ExecutionMode.LOOP, 3, 0, 60_000, 3, validation(), null, null); }
}
