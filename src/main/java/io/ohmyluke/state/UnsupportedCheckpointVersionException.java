package io.ohmyluke.state;

/** Signals that a checkpoint schema cannot be interpreted by this OML version. */
public final class UnsupportedCheckpointVersionException extends CheckpointException {
    public UnsupportedCheckpointVersionException(int actualVersion) {
        super("unsupported checkpoint schema version: " + actualVersion);
    }
}
