package io.ohmyluke.ai;

/** Allowlisted public failure reasons safe to persist in checkpoints and events. */
public enum AiFailureReason {
    SCRIPT_MISMATCH("AI test request did not match its scripted exchange"),
    SCRIPT_EXHAUSTED("AI test script has no exchange for this invocation"),
    AUTHENTICATION_REQUIRED("AI runtime authentication is required"),
    RATE_LIMITED("AI runtime rate limit was reached"),
    TIMED_OUT("AI runtime invocation timed out"),
    EXECUTION_FAILED("AI runtime execution failed"),
    INVALID_RESPONSE("AI runtime returned an invalid response"),
    UNKNOWN("AI runtime failed for an unspecified safe reason");

    private final String publicMessage;

    AiFailureReason(String publicMessage) {
        this.publicMessage = publicMessage;
    }

    public String publicMessage() {
        return publicMessage;
    }
}
