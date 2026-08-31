package io.ohmyluke.ai.codex;

import java.util.Objects;
import java.util.Optional;

/** Whether a run inherits or explicitly overrides Codex reasoning effort. */
public record CodexReasoningSelection(Optional<CodexReasoningEffort> explicitEffort) {
    public CodexReasoningSelection {
        explicitEffort = Objects.requireNonNull(explicitEffort, "explicitEffort");
    }

    public static CodexReasoningSelection inherit() {
        return new CodexReasoningSelection(Optional.empty());
    }

    public static CodexReasoningSelection explicit(CodexReasoningEffort effort) {
        return new CodexReasoningSelection(Optional.of(Objects.requireNonNull(effort, "effort")));
    }
}
