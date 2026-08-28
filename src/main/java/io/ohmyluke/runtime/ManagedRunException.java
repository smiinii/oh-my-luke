package io.ohmyluke.runtime;

/** Indicates that a persisted run cannot safely perform the requested operation. */
public final class ManagedRunException extends RuntimeException {
    public ManagedRunException(String message) {
        super(message);
    }
}
