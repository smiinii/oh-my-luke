package io.ohmyluke.graph;

/** Executable unit in a graph. */
public interface Node {
    NodeId id();

    /** Stable type, configuration, and behavior version included in persisted graph identity. */
    String fingerprint();

    NodeResult execute(NodeContext context) throws Exception;
}
