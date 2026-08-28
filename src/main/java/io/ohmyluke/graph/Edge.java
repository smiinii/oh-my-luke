package io.ohmyluke.graph;

import java.util.Objects;

/** Directed connection selected when its condition matches a node result. */
public record Edge(NodeId from, NodeId to, Condition condition) {
    public Edge {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(condition, "condition");
    }
}
