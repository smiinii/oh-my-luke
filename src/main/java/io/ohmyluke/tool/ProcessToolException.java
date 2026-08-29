package io.ohmyluke.tool;

/** Safe process setup or execution failure without raw command output. */
public final class ProcessToolException extends RuntimeException {
    public ProcessToolException(String message) {
        super(message);
    }

    public ProcessToolException(String message, Throwable cause) {
        super(message, cause);
    }
}
