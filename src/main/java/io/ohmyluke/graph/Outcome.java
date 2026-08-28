package io.ohmyluke.graph;

/** Result category produced by a node and consumed by edge conditions. */
public enum Outcome {
    SUCCESS,
    FAILURE,
    SKIPPED,
    CANCELLED
}
