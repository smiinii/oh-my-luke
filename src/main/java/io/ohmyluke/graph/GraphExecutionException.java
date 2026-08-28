package io.ohmyluke.graph;

/** Raised when a valid graph cannot continue deterministically at runtime. */
public final class GraphExecutionException extends RuntimeException {
    public GraphExecutionException(String message) {
        super(message);
    }

    public GraphExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
