package io.ohmyluke.policy;

import java.util.Objects;

/** Trusted, expiring approval bound to one exact command and run scope. */
public record CommandApprovalGrant(
        String runId,
        String projectRoot,
        String invocationId,
        CommandRisk risk,
        long expiresAtEpochMilli,
        String nonce) {
    public CommandApprovalGrant {
        runId = requireText(runId, "runId");
        projectRoot = requireText(projectRoot, "projectRoot");
        invocationId = requireText(invocationId, "invocationId");
        Objects.requireNonNull(risk, "risk");
        if (expiresAtEpochMilli < 0) {
            throw new IllegalArgumentException("expiresAtEpochMilli must not be negative");
        }
        nonce = requireText(nonce, "nonce");
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
