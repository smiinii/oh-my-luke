package io.ohmyluke.policy;

/** Stable capability categories used instead of an exact command-string allowlist. */
public enum ToolCapability {
    PROJECT_READ(DefaultPermission.ALLOW),
    PROJECT_WRITE(DefaultPermission.ALLOW),
    PROJECT_DELETE(DefaultPermission.ALLOW),
    LOCAL_PROCESS(DefaultPermission.ALLOW),
    LOCAL_GIT(DefaultPermission.ALLOW),

    BULK_DELETE(DefaultPermission.ASK),
    DEPENDENCY_INSTALL(DefaultPermission.ASK),
    NETWORK_ACCESS(DefaultPermission.ASK),
    OUTSIDE_PROJECT_ACCESS(DefaultPermission.ASK),
    EXTERNAL_WRITE(DefaultPermission.ASK),
    DOCKER_ACCESS(DefaultPermission.ASK),
    SECRET_USE(DefaultPermission.ASK),

    POLICY_MUTATION(DefaultPermission.DENY),
    SANDBOX_BYPASS(DefaultPermission.DENY),
    SECRET_DISCLOSURE(DefaultPermission.DENY),
    PROTECTED_SYSTEM_DAMAGE(DefaultPermission.DENY);

    private final DefaultPermission defaultPermission;

    ToolCapability(DefaultPermission defaultPermission) {
        this.defaultPermission = defaultPermission;
    }

    DefaultPermission defaultPermission() {
        return defaultPermission;
    }

    enum DefaultPermission {
        ALLOW,
        ASK,
        DENY
    }
}
