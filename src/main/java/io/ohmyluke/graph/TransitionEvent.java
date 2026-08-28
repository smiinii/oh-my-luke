package io.ohmyluke.graph;

import java.util.Map;
import java.util.Objects;

/** Reproducible evidence of one node execution and the selected edge. */
public record TransitionEvent(
        int step,
        NodeId node,
        Outcome outcome,
        NodeId nextNode,
        String selectionReason,
        StatePatch statePatch,
        Map<String, String> stateAfter,
        FailureInfo failure) {
    public TransitionEvent {
        if (step < 1) {
            throw new IllegalArgumentException("step must be positive");
        }
        Objects.requireNonNull(node, "node");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(nextNode, "nextNode");
        Objects.requireNonNull(selectionReason, "selectionReason");
        Objects.requireNonNull(statePatch, "statePatch");
        stateAfter = ImmutableStringMap.copyOf(stateAfter);
        if (outcome != Outcome.FAILURE && failure != null) {
            throw new IllegalArgumentException("failure identity is only valid for FAILURE outcomes");
        }
    }

    public TransitionEvent(
            int step,
            NodeId node,
            Outcome outcome,
            NodeId nextNode,
            String selectionReason,
            StatePatch statePatch,
            Map<String, String> stateAfter) {
        this(step, node, outcome, nextNode, selectionReason, statePatch, stateAfter, null);
    }
}
