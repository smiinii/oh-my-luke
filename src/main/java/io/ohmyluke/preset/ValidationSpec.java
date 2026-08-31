package io.ohmyluke.preset;

import java.util.List;
import java.util.Objects;

/** All supplied checks must pass. A command is fixed by the operator, never selected by AI. */
public record ValidationSpec(List<String> requiredText, List<String> forbiddenText, ValidationCommand command) {
    public ValidationSpec {
        requiredText = copy(requiredText);
        forbiddenText = copy(forbiddenText);
        if (requiredText.isEmpty() && forbiddenText.isEmpty() && command == null) {
            throw new IllegalArgumentException("at least one objective validation is required");
        }
    }

    private static List<String> copy(List<String> source) {
        List<String> values = List.copyOf(Objects.requireNonNull(source, "text checks"));
        if (values.size() > 32) { throw new IllegalArgumentException("too many text checks"); }
        values.forEach(value -> TaskSpec.text(value, 2_048, "text check"));
        return values;
    }
}
