package io.ohmyluke.graph;

import java.util.List;
import java.util.Objects;

/** Raised before execution when a graph violates structural or safety rules. */
public final class InvalidGraphException extends IllegalArgumentException {
    private final List<String> problems;

    public InvalidGraphException(List<String> problems) {
        super("invalid graph: " + String.join("; ", problems));
        this.problems = List.copyOf(Objects.requireNonNull(problems, "problems"));
        if (this.problems.isEmpty()) {
            throw new IllegalArgumentException("problems must not be empty");
        }
    }

    public List<String> problems() {
        return problems;
    }
}
