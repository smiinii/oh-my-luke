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
        Map<NodeId, List<Edge>> outgoingEdges = new HashMap<>();
        Set<NodeId> sourcesWithEdges = new HashSet<>();
        for (Edge edge : graph.edges()) {
            if (!nodesById.containsKey(edge.from())) {
                problems.add("edge has unknown source: " + edge.from());
            } else {
                sourcesWithEdges.add(edge.from());
                outgoingEdges.computeIfAbsent(edge.from(), ignored -> new ArrayList<>()).add(edge);
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

        validateDeterministicBranches(nodesById.keySet(), outgoingEdges, problems);

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
            String fingerprint = node.fingerprint();
            if (fingerprint == null || fingerprint.isBlank()) {
                problems.add("node fingerprint must not be blank: " + id);
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
        Map<NodeId, Integer> incomingEdgeCounts = new HashMap<>();
        for (NodeId node : executableNodes) {
            incomingEdgeCounts.put(node, 0);
        }
        for (NodeId source : executableNodes) {
            for (NodeId target : adjacency.getOrDefault(source, List.of())) {
                if (executableNodes.contains(target)) {
                    incomingEdgeCounts.merge(target, 1, Integer::sum);
                }
            }
        }

        ArrayDeque<NodeId> pending = new ArrayDeque<>();
        incomingEdgeCounts.forEach((node, count) -> {
            if (count == 0) {
                pending.add(node);
            }
        });

        int visitedCount = 0;
        while (!pending.isEmpty()) {
            NodeId current = pending.remove();
            visitedCount++;
            for (NodeId target : adjacency.getOrDefault(current, List.of())) {
                if (!executableNodes.contains(target)) {
                    continue;
                }
                int remaining = incomingEdgeCounts.merge(target, -1, Integer::sum);
                if (remaining == 0) {
                    pending.add(target);
                }
            }
        }

        return visitedCount != executableNodes.size();
    }

    private static void validateDeterministicBranches(
            Set<NodeId> executableNodes,
            Map<NodeId, List<Edge>> outgoingEdges,
            List<String> problems) {
        for (NodeId node : executableNodes) {
            List<Edge> candidates = outgoingEdges.getOrDefault(node, List.of());
            for (Outcome outcome : Outcome.values()) {
                long matchingCount = candidates.stream()
                        .filter(edge -> matchesOutcome(edge.condition(), outcome))
                        .count();
                if (matchingCount == 0) {
                    problems.add("no edge can match node " + node + " for outcome " + outcome);
                } else if (matchingCount > 1) {
                    problems.add("multiple edges can match node " + node + " for outcome " + outcome);
                }
            }
        }
    }

    private static boolean matchesOutcome(Condition condition, Outcome outcome) {
        if (condition instanceof Condition.Always) {
            return true;
        }
        Condition.OutcomeIs outcomeCondition = (Condition.OutcomeIs) condition;
        return outcomeCondition.expected() == outcome;
    }
}
