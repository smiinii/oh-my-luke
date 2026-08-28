package io.ohmyluke.policy;

/** Operator-impact classification attached to a trusted command rule. */
public enum CommandRisk {
    READ_ONLY(false),
    PROJECT_WRITE(false),
    EXTERNAL_CHANGE(true),
    DESTRUCTIVE(true);

    private final boolean requiresApproval;

    CommandRisk(boolean requiresApproval) {
        this.requiresApproval = requiresApproval;
    }

    public boolean requiresApproval() {
        return requiresApproval;
    }
}
