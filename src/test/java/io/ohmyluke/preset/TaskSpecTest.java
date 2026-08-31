package io.ohmyluke.preset;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class TaskSpecTest {
    @Test void rejectsEmptyValidationAndUnboundedAttempts() {
        assertThrows(IllegalArgumentException.class, () -> new ValidationSpec(List.of(), List.of(), null));
        assertThrows(IllegalArgumentException.class, () -> task(ExecutionMode.LOOP, 0));
        assertThrows(IllegalArgumentException.class, () -> task(ExecutionMode.DIRECT, 2));
        assertThrows(IllegalArgumentException.class, () -> task(ExecutionMode.LOOP, 21));
    }

    @Test void jsonIsStrictAndRoundTripsOptionalRuntimeSelection() {
        TaskSpec task = task(ExecutionMode.LOOP, 3);
        assertEquals(task, PresetJson.decode(PresetJson.encode(task), TaskSpec.class));
        assertEquals("chosen-model", task.withRuntimeSelection("chosen-model", "low").model());
        assertThrows(IllegalArgumentException.class,
                () -> PresetJson.decode("{\"path\":\"a\",\"content\":\"b\"} {}", EditProposal.class));
        assertThrows(IllegalArgumentException.class,
                () -> PresetJson.decode("{\"path\":\"a\",\"content\":\"b\",\"content\":\"c\"}", EditProposal.class));
        assertThrows(IllegalArgumentException.class,
                () -> PresetJson.decode("{\"path\":\"a\",\"content\":12}", EditProposal.class));
    }

    @Test void secretLookingInputsAreRejectedWithoutEchoingValues() {
        String value = "sk-" + "a".repeat(30);
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> new EditProposal("hello.txt", value));
        assertFalse(failure.getMessage().contains(value));
        assertThrows(IllegalArgumentException.class, () -> task(ExecutionMode.DIRECT, 1).withRuntimeSelection(value, null));
    }

    private static TaskSpec task(ExecutionMode mode, int count) {
        return new TaskSpec(1, "Goal", "hello.txt", mode, count, 0, 60_000, 2,
                new ValidationSpec(List.of("ready"), List.of(), null), null, null);
    }
}
