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
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(initialValues, "initialValues");
        validator.validate(graph);

        Map<NodeId, Node> nodesById = new LinkedHashMap<>();
        graph.nodes().forEach(node -> nodesById.put(node.id(), node));
        Map<NodeId, List<Edge>> outgoingEdges = new LinkedHashMap<>();
        graph.edges().forEach(edge -> outgoingEdges
                .computeIfAbsent(edge.from(), ignored -> new ArrayList<>())
                .add(edge));

        NodeId current = graph.start();
        int executedSteps = 0;
        Map<String, String> values = immutableState(initialValues);
        List<NodeId> path = new ArrayList<>();
        List<TransitionEvent> events = new ArrayList<>();
        path.add(current);

        if (graph.terminalNodes().contains(current)) {
            return snapshot(RunStatus.COMPLETED, current, executedSteps, values, path, events);
        }

        while (true) {
            if (graph.maxSteps() > 0 && executedSteps >= graph.maxSteps()) {
                return snapshot(
                        RunStatus.STEP_LIMIT_REACHED,
                        current,
                        executedSteps,
                        values,
                        path,
                        events);
            }

            Node node = nodesById.get(current);
            if (node == null) {
                throw new GraphExecutionException("no executable node found for " + current);
            }

            NodeResult result = execute(node, new NodeContext(values, executedSteps));
            Map<String, String> stateAfter = apply(values, result.statePatch());
            Edge selected = selectSingleEdge(
                    current,
                    outgoingEdges.getOrDefault(current, List.of()),
                    result,
                    stateAfter);

            executedSteps++;
            events.add(new TransitionEvent(
                    executedSteps,
                    current,
                    result.outcome(),
                    selected.to(),
                    selected.condition().description(),
                    result.statePatch(),
                    stateAfter));
            values = stateAfter;
            current = selected.to();
            path.add(current);

            if (graph.terminalNodes().contains(current)) {
                return snapshot(RunStatus.COMPLETED, current, executedSteps, values, path, events);
            }
        }
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
        LinkedHashMap<String, String> copy = new LinkedHashMap<>();
        values.forEach((key, value) -> copy.put(
                Objects.requireNonNull(key, "state key"),
                Objects.requireNonNull(value, "state value")));
        return Map.copyOf(copy);
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
