package io.ohmyluke.ai.codex;

import java.util.Objects;
import java.util.Optional;

/** Whether a run inherits the user's Codex model or explicitly overrides it. */
public record CodexModelSelection(Optional<String> explicitModel) {
    private static final int MAX_MODEL_LENGTH = 128;

    public CodexModelSelection {
        explicitModel = Objects.requireNonNull(explicitModel, "explicitModel")
                .map(CodexModelSelection::validateModel);
    }

    public static CodexModelSelection inherit() {
        return new CodexModelSelection(Optional.empty());
    }

    public static CodexModelSelection explicit(String model) {
        return new CodexModelSelection(Optional.of(validateModel(model)));
    }

    private static String validateModel(String value) {
        Objects.requireNonNull(value, "model");
        if (value.isBlank() || value.length() > MAX_MODEL_LENGTH) {
            throw new IllegalArgumentException("model must contain 1 to 128 characters");
        }
        if (value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("model must not contain control characters");
        }
        return value;
    }
}
