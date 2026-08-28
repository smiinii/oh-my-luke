package io.ohmyluke.state;

import java.util.List;
import java.util.Objects;

/** Human-oriented facts needed by the next process or agent. */
public record HandoffNote(
        String goal,
        List<String> confirmedFacts,
        List<String> changedFiles,
        List<String> remainingFailures,
        List<String> forbiddenAttempts,
        String nextAction) {
    public HandoffNote {
        goal = requireText(goal, "goal");
        confirmedFacts = copy(confirmedFacts, "confirmedFacts");
        changedFiles = copy(changedFiles, "changedFiles");
        remainingFailures = copy(remainingFailures, "remainingFailures");
        forbiddenAttempts = copy(forbiddenAttempts, "forbiddenAttempts");
        nextAction = requireText(nextAction, "nextAction");
    }

    private static List<String> copy(List<String> values, String name) {
        Objects.requireNonNull(values, name);
        return values.stream().map(value -> requireText(value, name + " item")).toList();
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
