package io.ohmyluke.ai.codex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CodexCliConfigurationTest {
    @TempDir
    Path project;

    @Test
    void inheritsCodexModelAndReasoningByDefault() {
        CodexCliConfiguration configuration = CodexCliConfiguration.defaults(project);

        assertEquals(CodexModelSelection.inherit(), configuration.modelSelection());
        assertEquals(CodexReasoningSelection.inherit(), configuration.reasoningSelection());
    }

    @Test
    void acceptsExplicitModelAndReasoningWithoutMaintainingAStaleModelAllowlist() {
        CodexCliConfiguration configuration = CodexCliConfiguration.defaults(project)
                .withModel("gpt-future-codex")
                .withReasoning(CodexReasoningEffort.HIGH);

        assertEquals("gpt-future-codex", configuration.modelSelection().explicitModel().orElseThrow());
        assertEquals(
                CodexReasoningEffort.HIGH,
                configuration.reasoningSelection().explicitEffort().orElseThrow());
    }

    @Test
    void rejectsControlCharactersInForwardedSettings() {
        CodexCliConfiguration configuration = CodexCliConfiguration.defaults(project);

        assertThrows(IllegalArgumentException.class, () -> configuration.withModel("gpt\nunsafe"));
        assertThrows(IllegalArgumentException.class, () -> configuration.withMaxInputBytes(16 * 1024 * 1024 + 1));
        assertThrows(IllegalArgumentException.class, () -> configuration.withMaxOutputBytes(64 * 1024 * 1024 + 1));
    }
}
