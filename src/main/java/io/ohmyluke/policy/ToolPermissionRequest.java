package io.ohmyluke.policy;

import java.nio.file.Path;
import java.util.Objects;

/** Model-independent description of the capability and exact target a tool wants to use. */
public record ToolPermissionRequest(
        String operationId,
        String runId,
        String projectRoot,
        ToolCapability capability,
        String target) {
    public ToolPermissionRequest(
            String operationId,
            String runId,
            Path projectRoot,
            ToolCapability capability,
            String target) {
        this(
                operationId,
                runId,
                normalizeProjectRoot(projectRoot),
                capability,
                target);
    }

    public ToolPermissionRequest {
        operationId = requireText(operationId, "operationId");
        runId = requireText(runId, "runId");
        projectRoot = requireText(projectRoot, "projectRoot");
        Objects.requireNonNull(capability, "capability");
        target = requireText(target, "target");
    }

    private static String normalizeProjectRoot(Path projectRoot) {
        return Objects.requireNonNull(projectRoot, "projectRoot")
                .toAbsolutePath()
                .normalize()
                .toString();
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(name + " must be non-blank and contain no NUL");
        }
        return value;
    }
}
