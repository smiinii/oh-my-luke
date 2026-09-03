package io.ohmyluke.cli;

import static org.junit.jupiter.api.Assertions.*;

import io.ohmyluke.preset.StartChoice;
import io.ohmyluke.preset.StartSpec;
import io.ohmyluke.preset.StartTaskSpec;
import io.ohmyluke.preset.ValidationSpec;
import io.ohmyluke.preset.WorkflowSpec;
import io.ohmyluke.preset.WorkflowStep;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class StartPromptTest {
    private final ByteArrayOutputStream output = new ByteArrayOutputStream();
    private final PrintStream out = new PrintStream(output, true, StandardCharsets.UTF_8);

    @Test void automaticChoiceNeedsOnlyOneAnswer() {
        AtomicInteger reads = new AtomicInteger();
        StartPrompt prompt = StartPrompt.interactive(() -> { reads.incrementAndGet(); return "1"; });
        assertEquals(StartChoice.AUTO, prompt.choose(task(false), out).orElseThrow());
        assertEquals(1, reads.get());
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("자동"));
    }

    @Test void manualChoiceOffersAllCompatibleModes() {
        assertEquals(StartChoice.DIRECT, prompt("2", "1").choose(task(false), out).orElseThrow());
        assertEquals(StartChoice.LOOP, prompt("2", "2").choose(task(false), out).orElseThrow());
        assertEquals(StartChoice.WORKFLOW, prompt("2", "3").choose(task(false), out).orElseThrow());
    }

    @Test void requiredApprovalCannotBeRemovedThroughTheManualMenu() {
        assertEquals(StartChoice.WORKFLOW, prompt("2", "direct", "1").choose(task(true), out).orElseThrow());
        String text = output.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("WORKFLOW"));
        assertFalse(text.contains("1. DIRECT"));
    }

    @Test void declaredWorkflowOnlyOffersWorkflowWithoutFlatteningTheDeclaration() {
        var workflow = new WorkflowSpec(1, "Check the file", "check", List.of(
                WorkflowStep.check("check", "hello.txt", validation(), "succeeded", "stopped")), 20, 0, 60_000);
        assertEquals(StartChoice.WORKFLOW, prompt("2", "1")
                .choose(new StartSpec(1, null, workflow), out).orElseThrow());
    }

    @Test void cancelAndEndOfInputNeverProduceAnImplicitSelection() {
        assertTrue(prompt("0").choose(task(false), out).isEmpty());
        assertTrue(prompt().choose(task(false), out).isEmpty());
        assertTrue(prompt("2").choose(task(false), out).isEmpty());
        assertTrue(prompt("2", "0").choose(task(false), out).isEmpty());
    }

    @Test void invalidOrEmptyInputIsRetriedButBounded() {
        assertEquals(StartChoice.AUTO, prompt("", "unknown", "1").choose(task(false), out).orElseThrow());
        assertThrows(IllegalArgumentException.class, () -> prompt("", "", "").choose(task(false), out));
        assertThrows(IllegalArgumentException.class, () -> prompt("2", "9", "9", "9").choose(task(false), out));
    }

    @Test void aNonInteractivePromptCannotReadInput() {
        StartPrompt prompt = StartPrompt.unavailable();
        assertFalse(prompt.isInteractive());
        assertThrows(IllegalStateException.class, () -> prompt.choose(task(false), out));
    }

    private static StartPrompt prompt(String... lines) {
        Queue<String> input = new ArrayDeque<>(List.of(lines));
        return StartPrompt.interactive(input::poll);
    }

    private static StartSpec task(boolean approval) {
        return new StartSpec(1, new StartTaskSpec("Make ready", "hello.txt", 3, 0, 60_000, 2,
                validation(), null, null, approval), null);
    }

    private static ValidationSpec validation() { return new ValidationSpec(List.of("ready"), List.of(), null); }
}
