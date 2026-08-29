package io.ohmyluke.tool;

/** Indicates that a file mutation could not be checkpointed or restored safely. */
public final class FileCheckpointException extends RuntimeException {
    public FileCheckpointException(String message) {
        super(message);
    }

    public FileCheckpointException(String message, Throwable cause) {
        super(message, cause);
    }
}
