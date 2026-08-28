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
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(state, "state");
        if (state.status() != RunStatus.RUNNING) {
            return state;
        }

        PreparedGraph prepared = prepare(graph);
        RunStatus status = state.status();
        NodeId current = state.currentNode();
        int executedSteps = state.executedSteps();
        Map<String, String> values = new LinkedHashMap<>(state.values());
        List<NodeId> path = new ArrayList<>(state.path());
        List<TransitionEvent> events = new ArrayList<>(state.events());

        while (status == RunStatus.RUNNING) {
            if (graph.maxSteps() > 0 && executedSteps >= graph.maxSteps()) {
                status = RunStatus.STEP_LIMIT_REACHED;
                break;
            }
            Node node = prepared.nodesById.get(current);
            if (node == null) {
                throw new GraphExecutionException("no executable node found for " + current);
            }
            NodeResult result = execute(node, new NodeContext(values, executedSteps));
            values.putAll(result.statePatch().updates());
            Map<String, String> stateAfter = immutableState(values);
            Edge selected = selectSingleEdge(
                    current,
                    prepared.outgoingEdges.getOrDefault(current, List.of()),
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
                    stateAfter,
                    result.failureInfo(),
                    result.metrics()));
            current = selected.to();
            path.add(current);
            status = nextStatus(graph, current, executedSteps);
        }
        return snapshot(status, current, executedSteps, values, path, events);
    }

    public RunState step(GraphDefinition graph, RunState state) {
        Objects.requireNonNull(graph, "graph");
        return step(prepare(graph), state);
    }

    public PreparedGraph prepare(GraphDefinition graph) {
        Objects.requireNonNull(graph, "graph");
        validator.validate(graph);
        return index(graph);
    }

    public RunState step(PreparedGraph prepared, RunState state) {
        Objects.requireNonNull(prepared, "prepared");
        Objects.requireNonNull(state, "state");
        if (state.status() != RunStatus.RUNNING) {
            return state;
        }
        GraphDefinition graph = prepared.graph;

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
        Node node = prepared.nodesById.get(current);
        if (node == null) {
            throw new GraphExecutionException("no executable node found for " + current);
        }

        NodeResult result = execute(node, new NodeContext(state.values(), state.executedSteps()));
        Map<String, String> stateAfter = apply(state.values(), result.statePatch());
        Edge selected = selectSingleEdge(
                current,
                prepared.outgoingEdges.getOrDefault(current, List.of()),
                result,
                stateAfter);

        int executedSteps = state.executedSteps() + 1;
        List<TransitionEvent> events = ImmutableAppendList.append(state.events(), new TransitionEvent(
                executedSteps,
                current,
                result.outcome(),
                selected.to(),
                selected.condition().description(),
                result.statePatch(),
                stateAfter,
                result.failureInfo(),
                result.metrics()));
        List<NodeId> path = ImmutableAppendList.append(state.path(), selected.to());

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

    private static PreparedGraph index(GraphDefinition graph) {
        Map<NodeId, Node> nodesById = new LinkedHashMap<>();
        graph.nodes().forEach(node -> nodesById.put(node.id(), node));
        Map<NodeId, List<Edge>> outgoingEdges = new LinkedHashMap<>();
        graph.edges().forEach(edge -> outgoingEdges
                .computeIfAbsent(edge.from(), ignored -> new ArrayList<>())
                .add(edge));
        return new PreparedGraph(graph, nodesById, outgoingEdges);
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

    public static final class PreparedGraph {
        private final GraphDefinition graph;
        private final Map<NodeId, Node> nodesById;
        private final Map<NodeId, List<Edge>> outgoingEdges;

        private PreparedGraph(
                GraphDefinition graph,
                Map<NodeId, Node> nodesById,
                Map<NodeId, List<Edge>> outgoingEdges) {
            this.graph = graph;
            this.nodesById = nodesById;
            this.outgoingEdges = outgoingEdges;
        }
    }
}
