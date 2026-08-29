package io.ohmyluke.graph;

import java.util.Map;

/** Read-only state snapshot provided to a node. */
public record NodeContext(
        Map<String, String> values,
        int executedSteps,
        String runId,
        boolean explicitRunScope) {
    public NodeContext {
        values = ImmutableStringMap.copyOf(values);
        if (executedSteps < 0) {
            throw new IllegalArgumentException("executedSteps must not be negative");
        }
        if (explicitRunScope && (runId == null || runId.isBlank())) {
            throw new IllegalArgumentException("explicit runId must not be blank");
        }
        if (!explicitRunScope && runId != null) {
            throw new IllegalArgumentException("implicit node context must not contain a runId");
        }
    }

    public NodeContext(Map<String, String> values, int executedSteps) {
        this(values, executedSteps, null, false);
    }

    public NodeContext(Map<String, String> values, int executedSteps, String runId) {
        this(values, executedSteps, runId, true);
    }
}
