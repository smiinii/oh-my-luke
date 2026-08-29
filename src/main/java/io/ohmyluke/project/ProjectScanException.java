package io.ohmyluke.project;

/** Raised when a project cannot be inspected safely and completely enough to build a profile. */
public final class ProjectScanException extends RuntimeException {
    public ProjectScanException(String message, Throwable cause) {
        super(message, cause);
    }
}
