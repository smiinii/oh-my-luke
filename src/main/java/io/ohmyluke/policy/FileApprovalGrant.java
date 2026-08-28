package io.ohmyluke.policy;

import java.util.Objects;

/** Trusted, operation-bound evidence that a user approved one exact file action. */
public record FileApprovalGrant(
        String approvalId,
        String runId,
        String projectRoot,
        String relativePath,
        FileAccess access,
        long expiresAtEpochMilli) {
    public FileApprovalGrant {
        approvalId = requireText(approvalId, "approvalId");
        runId = requireText(runId, "runId");
        projectRoot = requireText(projectRoot, "projectRoot");
        relativePath = requireText(relativePath, "relativePath");
        Objects.requireNonNull(access, "access");
        if (expiresAtEpochMilli < 0) {
            throw new IllegalArgumentException("expiresAtEpochMilli must not be negative");
        }
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
