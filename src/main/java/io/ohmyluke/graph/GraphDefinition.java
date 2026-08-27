package io.ohmyluke.graph;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Immutable graph configuration. A zero maxSteps means no limit for acyclic graphs only. */
public record GraphDefinition(
        NodeId start,
        Set<Node> nodes,
        List<Edge> edges,
        Set<NodeId> terminalNodes,
        int maxSteps) {
    public GraphDefinition {
        Objects.requireNonNull(start, "start");
        nodes = Set.copyOf(Objects.requireNonNull(nodes, "nodes"));
        edges = List.copyOf(Objects.requireNonNull(edges, "edges"));
        terminalNodes = Set.copyOf(Objects.requireNonNull(terminalNodes, "terminalNodes"));
    }
}
