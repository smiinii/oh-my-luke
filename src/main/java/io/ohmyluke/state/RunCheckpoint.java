package io.ohmyluke.state;

import io.ohmyluke.graph.RunState;
import io.ohmyluke.policy.PolicyConfiguration;
import io.ohmyluke.policy.PolicyState;
import java.util.Objects;

/** Versioned, graph-bound state persisted for one run. */
public record RunCheckpoint(
        int schemaVersion,
        String runId,
        String graphSignature,
        CheckpointPhase phase,
        RunState state,
        PolicyConfiguration policyConfiguration,
        PolicyState policyState) {
    public static final int CURRENT_SCHEMA_VERSION = 2;

    public RunCheckpoint {
        if (schemaVersion < 1) {
            throw new IllegalArgumentException("schemaVersion must be positive");
        }
        runId = requireText(runId, "runId");
        graphSignature = requireText(graphSignature, "graphSignature");
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(policyConfiguration, "policyConfiguration");
        Objects.requireNonNull(policyState, "policyState");
    }

    public static RunCheckpoint current(
            String runId,
            String graphSignature,
            CheckpointPhase phase,
            RunState state) {
        return current(
                runId,
                graphSignature,
                phase,
                state,
                PolicyConfiguration.unlimited(),
                PolicyState.initial(0));
    }

    public static RunCheckpoint current(
            String runId,
            String graphSignature,
            CheckpointPhase phase,
            RunState state,
            PolicyConfiguration policyConfiguration,
            PolicyState policyState) {
        return new RunCheckpoint(
                CURRENT_SCHEMA_VERSION,
                runId,
                graphSignature,
                phase,
                state,
                policyConfiguration,
                policyState);
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
