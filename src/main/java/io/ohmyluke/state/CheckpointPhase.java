package io.ohmyluke.state;

/** Whether the checkpoint is between nodes or records an interrupted node attempt. */
public enum CheckpointPhase {
    READY,
    NODE_STARTED
}
