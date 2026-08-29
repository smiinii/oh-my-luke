package io.ohmyluke.tool;

import io.ohmyluke.policy.ToolPermissionDecision;
import java.util.Arrays;
import java.util.Objects;

/** Structured result that never returns file bytes when permission was not granted. */
public record FileToolResult(
        ToolPermissionDecision permission,
        boolean executed,
        byte[] content,
        String checkpointId,
        String detail) {
    public FileToolResult {
        Objects.requireNonNull(permission, "permission");
        content = content == null ? new byte[0] : Arrays.copyOf(content, content.length);
        detail = requireText(detail, "detail");
        if (!executed && content.length > 0) {
            throw new IllegalArgumentException("a non-executed result must not expose content");
        }
        if (checkpointId != null && checkpointId.isBlank()) {
            throw new IllegalArgumentException("checkpointId must be null or non-blank");
        }
    }

    @Override
    public byte[] content() {
        return Arrays.copyOf(content, content.length);
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
