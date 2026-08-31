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
        PolicyState policyState,
        ApprovalState approval) {
    public static final int CURRENT_SCHEMA_VERSION = 3;

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
        if (approval != null && !approval.node().equals(state.currentNode())) {
            throw new IllegalArgumentException("approval must belong to the current node");
        }
    }

    public RunCheckpoint(int schemaVersion, String runId, String graphSignature, CheckpointPhase phase,
                         RunState state, PolicyConfiguration policyConfiguration, PolicyState policyState) {
        this(schemaVersion, runId, graphSignature, phase, state, policyConfiguration, policyState, null);
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
        return current(runId, graphSignature, phase, state, policyConfiguration, policyState, null);
    }

    public static RunCheckpoint current(String runId, String graphSignature, CheckpointPhase phase,
                                        RunState state, PolicyConfiguration policyConfiguration,
                                        PolicyState policyState, ApprovalState approval) {
        return new RunCheckpoint(
                CURRENT_SCHEMA_VERSION,
                runId,
                graphSignature,
                phase,
                state,
                policyConfiguration,
                policyState,
                approval);
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
