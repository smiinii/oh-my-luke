package io.ohmyluke.state;

/** Durable lifecycle events emitted by a managed graph run. */
public enum RunEventType {
    RUN_STARTED,
    RUN_RESUMED,
    NODE_STARTED,
    NODE_COMPLETED,
    RUN_COMPLETED,
    RUN_CANCELLED,
    CHECKPOINT_RECOVERED
}
