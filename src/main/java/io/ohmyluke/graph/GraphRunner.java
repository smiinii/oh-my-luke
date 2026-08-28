package io.ohmyluke.graph;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Executes a validated graph deterministically in memory. */
public final class GraphRunner {
    private final GraphValidator validator;

    public GraphRunner(GraphValidator validator) {
        this.validator = Objects.requireNonNull(validator, "validator");
    }

    public RunState run(GraphDefinition graph) {
        return run(graph, Map.of());
    }

    public RunState run(GraphDefinition graph, Map<String, String> initialValues) {
        return resume(graph, start(graph, initialValues));
    }

    public RunState start(GraphDefinition graph) {
        return start(graph, Map.of());
    }

    public RunState start(GraphDefinition graph, Map<String, String> initialValues) {
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(initialValues, "initialValues");
        validator.validate(graph);

        NodeId current = graph.start();
        RunStatus status = graph.terminalNodes().contains(current)
                ? RunStatus.COMPLETED
                : RunStatus.RUNNING;
        return snapshot(
                status,
                current,
                0,
                immutableState(initialValues),
                List.of(current),
                List.of());
    }

    public RunState resume(GraphDefinition graph, RunState state) {
        Objects.requireNonNull(state, "state");
        RunState current = state;
        while (current.status() == RunStatus.RUNNING) {
            current = step(graph, current);
        }
        return current;
    }

    public RunState step(GraphDefinition graph, RunState state) {
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(state, "state");
        validator.validate(graph);
        if (state.status() != RunStatus.RUNNING) {
            return state;
        }

        Map<NodeId, Node> nodesById = new LinkedHashMap<>();
        graph.nodes().forEach(node -> nodesById.put(node.id(), node));
        Map<NodeId, List<Edge>> outgoingEdges = new LinkedHashMap<>();
        graph.edges().forEach(edge -> outgoingEdges
                .computeIfAbsent(edge.from(), ignored -> new ArrayList<>())
                .add(edge));

        if (graph.maxSteps() > 0 && state.executedSteps() >= graph.maxSteps()) {
            return snapshot(
                    RunStatus.STEP_LIMIT_REACHED,
                    state.currentNode(),
                    state.executedSteps(),
                    state.values(),
                    state.path(),
                    state.events());
        }

        NodeId current = state.currentNode();
        Node node = nodesById.get(current);
        if (node == null) {
            throw new GraphExecutionException("no executable node found for " + current);
        }

        NodeResult result = execute(node, new NodeContext(state.values(), state.executedSteps()));
        Map<String, String> stateAfter = apply(state.values(), result.statePatch());
        Edge selected = selectSingleEdge(
                current,
                outgoingEdges.getOrDefault(current, List.of()),
                result,
                stateAfter);

        int executedSteps = state.executedSteps() + 1;
        List<TransitionEvent> events = new ArrayList<>(state.events());
        events.add(new TransitionEvent(
                executedSteps,
                current,
                result.outcome(),
                selected.to(),
                selected.condition().description(),
                result.statePatch(),
                stateAfter));
        List<NodeId> path = new ArrayList<>(state.path());
        path.add(selected.to());

        RunStatus status = nextStatus(graph, selected.to(), executedSteps);
        return snapshot(status, selected.to(), executedSteps, stateAfter, path, events);
    }

    private static RunStatus nextStatus(GraphDefinition graph, NodeId current, int executedSteps) {
        if (graph.terminalNodes().contains(current)) {
            return RunStatus.COMPLETED;
        }
        if (graph.maxSteps() > 0 && executedSteps >= graph.maxSteps()) {
            return RunStatus.STEP_LIMIT_REACHED;
        }
        return RunStatus.RUNNING;
    }

    private static NodeResult execute(Node node, NodeContext context) {
        try {
            return Objects.requireNonNull(node.execute(context), "node result");
        } catch (GraphExecutionException error) {
            throw error;
        } catch (Exception error) {
            throw new GraphExecutionException("node execution failed: " + node.id(), error);
        }
    }

    private static Map<String, String> apply(Map<String, String> current, StatePatch patch) {
        LinkedHashMap<String, String> updated = new LinkedHashMap<>(current);
        updated.putAll(patch.updates());
        return immutableState(updated);
    }

    private static Edge selectSingleEdge(
            NodeId node,
            List<Edge> candidates,
            NodeResult result,
            Map<String, String> stateAfter) {
        List<Edge> matching = candidates.stream()
                .filter(edge -> edge.condition().matches(result, stateAfter))
                .toList();
        if (matching.isEmpty()) {
            throw new GraphExecutionException(
                    "no edge matched node " + node + " with outcome " + result.outcome());
        }
        if (matching.size() > 1) {
            throw new GraphExecutionException(
                    "multiple edges matched node " + node + " with outcome " + result.outcome());
        }
        return matching.getFirst();
    }

    private static Map<String, String> immutableState(Map<String, String> values) {
        return ImmutableStringMap.copyOf(values);
    }

    private static RunState snapshot(
            RunStatus status,
            NodeId current,
            int executedSteps,
            Map<String, String> values,
            List<NodeId> path,
            List<TransitionEvent> events) {
        return new RunState(status, current, executedSteps, values, path, events);
    }
}
