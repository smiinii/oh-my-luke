package io.ohmyluke.policy;

import java.util.Objects;

/** Trusted, operation-bound evidence that a user approved one exact file action. */
public record FileApprovalGrant(
        String approvalId,
        String projectRoot,
        String relativePath,
        FileAccess access) {
    public FileApprovalGrant {
        approvalId = requireText(approvalId, "approvalId");
        projectRoot = requireText(projectRoot, "projectRoot");
        relativePath = requireText(relativePath, "relativePath");
        Objects.requireNonNull(access, "access");
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
