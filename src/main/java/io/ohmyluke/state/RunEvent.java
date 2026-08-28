package io.ohmyluke.state;

import io.ohmyluke.graph.NodeId;
import io.ohmyluke.graph.RunStatus;
import java.util.Objects;

/** One append-only JSONL record describing run progress. */
public record RunEvent(
        int schemaVersion,
        String runId,
        long sequence,
        RunEventType type,
        NodeId node,
        RunStatus status,
        int executedSteps,
        String detail) {
    public static final int CURRENT_SCHEMA_VERSION = 1;

    public RunEvent {
        if (schemaVersion < 1) {
            throw new IllegalArgumentException("schemaVersion must be positive");
        }
        Objects.requireNonNull(runId, "runId");
        if (runId.isBlank()) {
            throw new IllegalArgumentException("runId must not be blank");
        }
        if (sequence < 1) {
            throw new IllegalArgumentException("sequence must be positive");
        }
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(node, "node");
        Objects.requireNonNull(status, "status");
        if (executedSteps < 0) {
            throw new IllegalArgumentException("executedSteps must not be negative");
        }
        Objects.requireNonNull(detail, "detail");
    }

    public static RunEvent current(
            String runId,
            long sequence,
            RunEventType type,
            NodeId node,
            RunStatus status,
            int executedSteps,
            String detail) {
        return new RunEvent(
                CURRENT_SCHEMA_VERSION,
                runId,
                sequence,
                type,
                node,
                status,
                executedSteps,
                detail);
    }
}
