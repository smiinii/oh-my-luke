package io.ohmyluke.policy;

import java.util.List;
import java.util.Objects;

/** Closed set of objective, composable completion predicates. */
public sealed interface CompletionCondition {
    record All(List<CompletionCondition> conditions) implements CompletionCondition {
        public All {
            conditions = nonEmpty(conditions, "all conditions");
        }
    }

    record Any(List<CompletionCondition> conditions) implements CompletionCondition {
        public Any {
            conditions = nonEmpty(conditions, "any conditions");
        }
    }

    record CommandExitCode(CommandInvocation command, int expected) implements CompletionCondition {
        public CommandExitCode {
            Objects.requireNonNull(command, "command");
        }
    }

    record FileExists(String path) implements CompletionCondition {
        public FileExists {
            path = requireText(path, "path");
        }
    }

    record UnresolvedCriticalIssues(int expected) implements CompletionCondition {
        public UnresolvedCriticalIssues {
            if (expected < 0) {
                throw new IllegalArgumentException("expected must not be negative");
            }
        }
    }

    record RequirementSatisfied(String requirement) implements CompletionCondition {
        public RequirementSatisfied {
            requirement = requireText(requirement, "requirement");
        }
    }

    private static List<CompletionCondition> nonEmpty(
            List<CompletionCondition> conditions,
            String name) {
        List<CompletionCondition> copy = List.copyOf(Objects.requireNonNull(conditions, name));
        if (copy.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be empty");
        }
        return copy;
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
