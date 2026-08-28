package io.ohmyluke.graph;

import java.util.Map;

/** Read-only state snapshot provided to a node. */
public record NodeContext(Map<String, String> values, int executedSteps) {
    public NodeContext {
        values = ImmutableStringMap.copyOf(values);
        if (executedSteps < 0) {
            throw new IllegalArgumentException("executedSteps must not be negative");
        }
    }
}
