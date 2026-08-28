package io.ohmyluke.policy;

import java.util.Objects;

/** Trusted approval evidence bound to a capability, target, project, and selected scope. */
public record ToolPermissionGrant(
        String grantId,
        ApprovalScope scope,
        String projectRoot,
        String runId,
        String operationId,
        ToolCapability capability,
        String target,
        long expiresAtEpochMilli) {
    public ToolPermissionGrant {
        grantId = requireText(grantId, "grantId");
        Objects.requireNonNull(scope, "scope");
        projectRoot = requireText(projectRoot, "projectRoot");
        Objects.requireNonNull(capability, "capability");
        target = requireText(target, "target");
        if (expiresAtEpochMilli < 0) {
            throw new IllegalArgumentException("expiresAtEpochMilli must not be negative");
        }
        if (scope == ApprovalScope.PROJECT) {
            runId = null;
            operationId = null;
        } else {
            runId = requireText(runId, "runId");
            operationId = scope == ApprovalScope.ONCE
                    ? requireText(operationId, "operationId")
                    : null;
        }
    }

    public static ToolPermissionGrant once(
            String grantId,
            ToolPermissionRequest request,
            long expiresAtEpochMilli) {
        return from(grantId, ApprovalScope.ONCE, request, expiresAtEpochMilli);
    }

    public static ToolPermissionGrant forRun(
            String grantId,
            ToolPermissionRequest request,
            long expiresAtEpochMilli) {
        return from(grantId, ApprovalScope.RUN, request, expiresAtEpochMilli);
    }

    public static ToolPermissionGrant forProject(
            String grantId,
            ToolPermissionRequest request,
            long expiresAtEpochMilli) {
        return from(grantId, ApprovalScope.PROJECT, request, expiresAtEpochMilli);
    }

    boolean matches(ToolPermissionRequest request, long nowEpochMilli) {
        if (expiresAtEpochMilli <= nowEpochMilli
                || !projectRoot.equals(request.projectRoot())
                || capability != request.capability()
                || !target.equals(request.target())) {
            return false;
        }
        return switch (scope) {
            case ONCE -> runId.equals(request.runId()) && operationId.equals(request.operationId());
            case RUN -> runId.equals(request.runId());
            case PROJECT -> true;
        };
    }

    private static ToolPermissionGrant from(
            String grantId,
            ApprovalScope scope,
            ToolPermissionRequest request,
            long expiresAtEpochMilli) {
        Objects.requireNonNull(request, "request");
        return new ToolPermissionGrant(
                grantId,
                scope,
                request.projectRoot(),
                request.runId(),
                request.operationId(),
                request.capability(),
                request.target(),
                expiresAtEpochMilli);
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(name + " must be non-blank and contain no NUL");
        }
        return value;
    }
}
