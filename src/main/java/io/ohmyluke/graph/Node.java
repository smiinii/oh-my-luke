package io.ohmyluke.graph;

/** Executable unit in a graph. */
public interface Node {
    NodeId id();

    NodeResult execute(NodeContext context) throws Exception;
}
