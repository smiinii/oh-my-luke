package io.ohmyluke.policy;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Immutable objective facts supplied by deterministic validators. */
public record CompletionFacts(
        Map<CommandInvocation, Integer> commandExitCodes,
        Set<String> existingFiles,
        int unresolvedCriticalIssues,
        Set<String> satisfiedRequirements) {
    public CompletionFacts {
        commandExitCodes = Map.copyOf(Objects.requireNonNull(commandExitCodes, "commandExitCodes"));
        existingFiles = Set.copyOf(Objects.requireNonNull(existingFiles, "existingFiles"));
        if (unresolvedCriticalIssues < 0) {
            throw new IllegalArgumentException("unresolvedCriticalIssues must not be negative");
        }
        satisfiedRequirements = Set.copyOf(
                Objects.requireNonNull(satisfiedRequirements, "satisfiedRequirements"));
        existingFiles.forEach(path -> requireText(path, "existing file"));
        satisfiedRequirements.forEach(requirement -> requireText(requirement, "requirement"));
    }

    private static void requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
