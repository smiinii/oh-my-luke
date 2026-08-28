package io.ohmyluke.policy;

import java.time.Clock;
import java.util.Objects;

/** Evaluates hard invariants, friction-free defaults, remembered grants, and project autonomy. */
public final class ToolPermissionPolicy implements ToolPermissionEvaluator {
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

    @Override
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
                    "이 프로젝트에서 사용자가 설정한 자율 실행 권한에 따라 승인 없이 실행합니다.",
                    null);
        }
        return grantLedger.consumeMatching(request, clock.millis())
                .map(grant -> ToolPermissionDecision.allow(
                        "permission.remembered-grant",
                        PermissionMessages.reused(request),
                        grant.grantId()))
                .orElseGet(() -> ToolPermissionDecision.ask(
                        "permission.user-choice-required",
                        PermissionMessages.prompt(request)));
    }
}
