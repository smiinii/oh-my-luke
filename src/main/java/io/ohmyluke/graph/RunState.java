package io.ohmyluke.graph;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable result and in-memory checkpoint of a graph run. */
public record RunState(
        RunStatus status,
        NodeId currentNode,
        int executedSteps,
        Map<String, String> values,
        List<NodeId> path,
        List<TransitionEvent> events) {
    public RunState {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(currentNode, "currentNode");
        if (executedSteps < 0) {
            throw new IllegalArgumentException("executedSteps must not be negative");
        }
        values = Map.copyOf(Objects.requireNonNull(values, "values"));
        path = List.copyOf(Objects.requireNonNull(path, "path"));
        events = List.copyOf(Objects.requireNonNull(events, "events"));
    }
}
