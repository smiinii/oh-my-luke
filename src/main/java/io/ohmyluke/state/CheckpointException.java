package io.ohmyluke.state;

/** Signals that a checkpoint could not be encoded, persisted, or restored safely. */
public class CheckpointException extends RuntimeException {
    public CheckpointException(String message) {
        super(message);
    }

    public CheckpointException(String message, Throwable cause) {
        super(message, cause);
    }
}
