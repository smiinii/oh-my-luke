package io.ohmyluke.ai;

/** Allowlisted AI failures with stable codes and public messages safe for persistence. */
public enum AiFailureCode {
    SCRIPT_MISMATCH(
            "fake.request-mismatch",
            "AI test request did not match its scripted exchange"),
    SCRIPT_EXHAUSTED(
            "fake.script-exhausted",
            "AI test script has no exchange for this invocation"),
    AUTHENTICATION_REQUIRED(
            "runtime.authentication-required",
            "AI runtime authentication is required"),
    RATE_LIMITED(
            "runtime.rate-limited",
            "AI runtime rate limit was reached"),
    TIMED_OUT(
            "runtime.timed-out",
            "AI runtime invocation timed out"),
    RUNTIME_UNAVAILABLE(
            "runtime.unavailable",
            "AI runtime is not installed or could not be started"),
    INPUT_LIMIT_EXCEEDED(
            "runtime.input-limit-exceeded",
            "AI runtime input exceeded the configured limit"),
    OUTPUT_LIMIT_EXCEEDED(
            "runtime.output-limit-exceeded",
            "AI runtime output exceeded the configured limit"),
    REQUEST_CONFLICT(
            "runtime.request-conflict",
            "AI invocation was already used with a different request or runtime configuration"),
    EXECUTION_FAILED(
            "runtime.execution-failed",
            "AI runtime execution failed"),
    INVALID_RESPONSE(
            "runtime.invalid-response",
            "AI runtime returned an invalid response"),
    UNKNOWN(
            "runtime.unknown",
            "AI runtime failed for an unspecified safe reason");

    private final String stableCode;
    private final String publicMessage;

    AiFailureCode(String stableCode, String publicMessage) {
        this.stableCode = stableCode;
        this.publicMessage = publicMessage;
    }

    public String stableCode() {
        return stableCode;
    }

    public String publicMessage() {
        return publicMessage;
    }
}
