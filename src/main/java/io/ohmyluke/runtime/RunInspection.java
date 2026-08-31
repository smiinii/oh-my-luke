package io.ohmyluke.runtime;

import io.ohmyluke.graph.RunState;
import io.ohmyluke.policy.PolicyConfiguration;
import io.ohmyluke.policy.PolicyState;
import io.ohmyluke.state.CheckpointPhase;
import io.ohmyluke.state.RunEvent;
import io.ohmyluke.state.ApprovalState;
import java.util.List;
import java.util.Objects;

/** Read-only view of the durable state and event history for one run. */
public record RunInspection(
        String runId,
        String graphSignature,
        CheckpointPhase phase,
        RunState state,
        PolicyConfiguration policyConfiguration,
        PolicyState policyState,
        boolean recoveredFromBackup,
        List<RunEvent> events,
        boolean ignoredIncompleteEventTail,
        ApprovalState approval) {
    public RunInspection {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(graphSignature, "graphSignature");
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(policyConfiguration, "policyConfiguration");
        Objects.requireNonNull(policyState, "policyState");
        events = List.copyOf(Objects.requireNonNull(events, "events"));
    }

    public RunInspection(String runId, String graphSignature, CheckpointPhase phase, RunState state,
                         PolicyConfiguration configuration, PolicyState policyState, boolean recoveredFromBackup,
                         List<RunEvent> events, boolean ignoredIncompleteEventTail) {
        this(runId, graphSignature, phase, state, configuration, policyState, recoveredFromBackup,
                events, ignoredIncompleteEventTail, null);
    }
}
