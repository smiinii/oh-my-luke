package io.ohmyluke.graph;

import java.util.Map;
import java.util.Objects;

/** Read-only state snapshot provided to a node. */
public record NodeContext(Map<String, String> values, int executedSteps) {
    public NodeContext {
        values = Map.copyOf(Objects.requireNonNull(values, "values"));
        if (executedSteps < 0) {
            throw new IllegalArgumentException("executedSteps must not be negative");
        }
    }
}
