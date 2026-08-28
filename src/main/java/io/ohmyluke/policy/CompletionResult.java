package io.ohmyluke.policy;

import java.util.List;
import java.util.Objects;

/** Result and ordered leaf evidence of evaluating one completion expression. */
public record CompletionResult(boolean satisfied, List<ConditionEvidence> evidence) {
    public CompletionResult {
        evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence"));
        if (evidence.isEmpty()) {
            throw new IllegalArgumentException("evidence must not be empty");
        }
    }
}
