package io.ohmyluke.graph;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Performs deterministic structural and loop-safety checks before execution. */
public final class GraphValidator {
    public void validate(GraphDefinition graph) {
        Objects.requireNonNull(graph, "graph");
        List<String> problems = new ArrayList<>();
        Map<NodeId, Node> nodesById = indexNodes(graph.nodes(), problems);

        if (graph.maxSteps() < 0) {
            problems.add("maxSteps must not be negative");
        }
        if (graph.terminalNodes().isEmpty()) {
            problems.add("at least one terminal node is required");
        }
        for (NodeId terminal : graph.terminalNodes()) {
            if (nodesById.containsKey(terminal)) {
                problems.add("terminal node must not also be executable: " + terminal);
            }
        }

        Set<NodeId> knownIds = new HashSet<>(nodesById.keySet());
        knownIds.addAll(graph.terminalNodes());
        if (!knownIds.contains(graph.start())) {
            problems.add("start node is unknown: " + graph.start());
        }

        Map<NodeId, List<NodeId>> adjacency = new HashMap<>();
        Set<NodeId> sourcesWithEdges = new HashSet<>();
        for (Edge edge : graph.edges()) {
            if (!nodesById.containsKey(edge.from())) {
                problems.add("edge has unknown source: " + edge.from());
            } else {
                sourcesWithEdges.add(edge.from());
            }
            if (!knownIds.contains(edge.to())) {
                problems.add("edge has unknown target: " + edge.to());
            }
            if (graph.terminalNodes().contains(edge.from())) {
                problems.add("terminal node must not have outgoing edges: " + edge.from());
            }
            if (knownIds.contains(edge.from()) && knownIds.contains(edge.to())) {
                adjacency.computeIfAbsent(edge.from(), ignored -> new ArrayList<>()).add(edge.to());
            }
        }

        for (NodeId nodeId : nodesById.keySet()) {
            if (!sourcesWithEdges.contains(nodeId)) {
                problems.add("executable node has no outgoing edge: " + nodeId);
            }
        }

        Set<NodeId> reachable = reachableFrom(graph.start(), adjacency, knownIds);
        for (NodeId nodeId : nodesById.keySet()) {
            if (!reachable.contains(nodeId)) {
                problems.add("executable node is unreachable: " + nodeId);
            }
        }
        if (graph.terminalNodes().stream().noneMatch(reachable::contains)) {
            problems.add("no terminal node is reachable from start");
        }

        if (containsCycle(nodesById.keySet(), adjacency) && graph.maxSteps() == 0) {
            problems.add("cyclic graph requires a positive step limit");
        }

        if (!problems.isEmpty()) {
            throw new InvalidGraphException(problems);
        }
    }

    private static Map<NodeId, Node> indexNodes(Set<Node> nodes, List<String> problems) {
        Map<NodeId, Node> indexed = new LinkedHashMap<>();
        for (Node node : nodes) {
            if (node == null) {
                problems.add("node must not be null");
                continue;
            }
            NodeId id = node.id();
            if (id == null) {
                problems.add("node id must not be null");
                continue;
            }
            if (indexed.putIfAbsent(id, node) != null) {
                problems.add("duplicate node id: " + id);
            }
        }
        return indexed;
    }

    private static Set<NodeId> reachableFrom(
            NodeId start,
            Map<NodeId, List<NodeId>> adjacency,
            Set<NodeId> knownIds) {
        if (!knownIds.contains(start)) {
            return Set.of();
        }
        Set<NodeId> visited = new HashSet<>();
        ArrayDeque<NodeId> pending = new ArrayDeque<>();
        pending.push(start);
        while (!pending.isEmpty()) {
            NodeId current = pending.pop();
            if (!visited.add(current)) {
                continue;
            }
            for (NodeId next : adjacency.getOrDefault(current, List.of())) {
                if (knownIds.contains(next)) {
                    pending.push(next);
                }
            }
        }
        return visited;
    }

    private static boolean containsCycle(
            Set<NodeId> executableNodes,
            Map<NodeId, List<NodeId>> adjacency) {
        Set<NodeId> visiting = new HashSet<>();
        Set<NodeId> visited = new HashSet<>();
        for (NodeId node : executableNodes) {
            if (containsCycleFrom(node, executableNodes, adjacency, visiting, visited)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsCycleFrom(
            NodeId node,
            Set<NodeId> executableNodes,
            Map<NodeId, List<NodeId>> adjacency,
            Set<NodeId> visiting,
            Set<NodeId> visited) {
        if (visited.contains(node)) {
            return false;
        }
        if (!visiting.add(node)) {
            return true;
        }
        for (NodeId next : adjacency.getOrDefault(node, List.of())) {
            if (executableNodes.contains(next)
                    && containsCycleFrom(next, executableNodes, adjacency, visiting, visited)) {
                return true;
            }
        }
        visiting.remove(node);
        visited.add(node);
        return false;
    }
}
