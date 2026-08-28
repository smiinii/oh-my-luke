package io.ohmyluke.policy;

import java.time.Clock;
import java.util.Objects;

/** Evaluates hard invariants, friction-free defaults, remembered grants, and project autonomy. */
public final class ToolPermissionPolicy {
    private final PermissionGrantLedger grantLedger;
    private final boolean autonomousProject;
    private final Clock clock;

    public ToolPermissionPolicy(
            PermissionGrantLedger grantLedger,
            boolean autonomousProject,
            Clock clock) {
        this.grantLedger = Objects.requireNonNull(grantLedger, "grantLedger");
        this.autonomousProject = autonomousProject;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public ToolPermissionDecision evaluate(ToolPermissionRequest request) {
        Objects.requireNonNull(request, "request");
        return switch (request.capability().defaultPermission()) {
            case DENY -> ToolPermissionDecision.deny(
                    "permission.invariant-deny",
                    "OML safety invariants cannot be overridden by autonomy or a stored approval");
            case ALLOW -> ToolPermissionDecision.allow(
                    "permission.default-allow",
                    "Reversible work inside the project is allowed without approval",
                    null);
            case ASK -> evaluateAskClass(request);
        };
    }

    private ToolPermissionDecision evaluateAskClass(ToolPermissionRequest request) {
        if (autonomousProject) {
            return ToolPermissionDecision.allow(
                    "permission.autonomous-project",
                    "The user enabled autonomous execution for this project",
                    null);
        }
        return grantLedger.consumeMatching(request, clock.millis())
                .map(grant -> ToolPermissionDecision.allow(
                        "permission.remembered-grant",
                        "A matching user approval was applied without prompting again",
                        grant.grantId()))
                .orElseGet(() -> ToolPermissionDecision.ask(
                        "permission.user-choice-required",
                        "Choose once, current run, current project, or deny"));
    }
}
