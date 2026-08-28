package io.ohmyluke.state;

import io.ohmyluke.graph.RunState;
import java.util.Objects;

/** Versioned, graph-bound state persisted for one run. */
public record RunCheckpoint(
        int schemaVersion,
        String runId,
        String graphSignature,
        CheckpointPhase phase,
        RunState state) {
    public static final int CURRENT_SCHEMA_VERSION = 1;

    public RunCheckpoint {
        if (schemaVersion < 1) {
            throw new IllegalArgumentException("schemaVersion must be positive");
        }
        runId = requireText(runId, "runId");
        graphSignature = requireText(graphSignature, "graphSignature");
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(state, "state");
    }

    public static RunCheckpoint current(
            String runId,
            String graphSignature,
            CheckpointPhase phase,
            RunState state) {
        return new RunCheckpoint(CURRENT_SCHEMA_VERSION, runId, graphSignature, phase, state);
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
