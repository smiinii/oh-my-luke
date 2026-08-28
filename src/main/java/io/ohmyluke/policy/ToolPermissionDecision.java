package io.ohmyluke.policy;

import java.util.Objects;
import java.util.regex.Pattern;

/** Decision returned before a structured tool is allowed to execute. */
public record ToolPermissionDecision(
        ToolPermission permission,
        String reasonCode,
        String detail,
        String grantId) {
    private static final Pattern REASON_CODE = Pattern.compile("[a-z][a-z0-9]*(?:[._-][a-z0-9]+)+");

    public ToolPermissionDecision {
        Objects.requireNonNull(permission, "permission");
        reasonCode = requireText(reasonCode, "reasonCode");
        if (!REASON_CODE.matcher(reasonCode).matches()) {
            throw new IllegalArgumentException("reasonCode must be stable kebab/dot notation");
        }
        detail = requireText(detail, "detail");
        if (grantId != null && grantId.isBlank()) {
            throw new IllegalArgumentException("grantId must be null or non-blank");
        }
        if (permission != ToolPermission.ALLOW && grantId != null) {
            throw new IllegalArgumentException("only ALLOW decisions may reference a grant");
        }
    }

    public static ToolPermissionDecision allow(String reasonCode, String detail, String grantId) {
        return new ToolPermissionDecision(ToolPermission.ALLOW, reasonCode, detail, grantId);
    }

    public static ToolPermissionDecision ask(String reasonCode, String detail) {
        return new ToolPermissionDecision(ToolPermission.ASK, reasonCode, detail, null);
    }

    public static ToolPermissionDecision deny(String reasonCode, String detail) {
        return new ToolPermissionDecision(ToolPermission.DENY, reasonCode, detail, null);
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
