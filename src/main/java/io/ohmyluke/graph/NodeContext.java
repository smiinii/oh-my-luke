package io.ohmyluke.graph;

import java.util.Map;

/** Read-only state snapshot provided to a node. */
public record NodeContext(Map<String, String> values, int executedSteps, String runId) {
    public static final String LOCAL_RUN_ID = "local";

    public NodeContext {
        values = ImmutableStringMap.copyOf(values);
        if (executedSteps < 0) {
            throw new IllegalArgumentException("executedSteps must not be negative");
        }
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId must not be blank");
        }
    }

    public NodeContext(Map<String, String> values, int executedSteps) {
        this(values, executedSteps, LOCAL_RUN_ID);
    }
}
