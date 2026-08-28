package io.ohmyluke.policy;

import java.util.Objects;

/** Reproducible evidence for one leaf completion condition. */
public record ConditionEvidence(
        String conditionId,
        boolean satisfied,
        String expected,
        String actual) {
    public ConditionEvidence {
        Objects.requireNonNull(conditionId, "conditionId");
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(actual, "actual");
    }
}
