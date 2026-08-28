package io.ohmyluke.state;

import io.ohmyluke.policy.ToolPermissionGrant;
import java.util.List;
import java.util.Objects;

/** Versioned operator-owned permission settings stored outside AI-visible tool access. */
public record ProjectPermissionSettings(
        int schemaVersion,
        String projectRoot,
        boolean autonomousProject,
        List<ToolPermissionGrant> grants) {
    public static final int CURRENT_SCHEMA_VERSION = 1;

    public ProjectPermissionSettings {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported permission schemaVersion: " + schemaVersion);
        }
        Objects.requireNonNull(projectRoot, "projectRoot");
        if (projectRoot.isBlank()) {
            throw new IllegalArgumentException("projectRoot must not be blank");
        }
        grants = List.copyOf(Objects.requireNonNull(grants, "grants"));
    }

    public static ProjectPermissionSettings defaults(String projectRoot) {
        return new ProjectPermissionSettings(CURRENT_SCHEMA_VERSION, projectRoot, false, List.of());
    }
}
