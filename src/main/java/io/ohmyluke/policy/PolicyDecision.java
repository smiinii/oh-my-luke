package io.ohmyluke.policy;

import java.util.Objects;
import java.util.regex.Pattern;

/** Machine-readable policy outcome with a stable reason and operator-facing detail. */
public record PolicyDecision(
        PolicyOutcome outcome,
        String reasonCode,
        String detail,
        boolean resumable) {
    private static final Pattern REASON_CODE = Pattern.compile("[a-z][a-z0-9]*(?:[._-][a-z0-9]+)+");

    public PolicyDecision {
        Objects.requireNonNull(outcome, "outcome");
        reasonCode = requireText(reasonCode, "reasonCode");
        if (!REASON_CODE.matcher(reasonCode).matches()) {
            throw new IllegalArgumentException("reasonCode must be stable kebab/dot notation");
        }
        detail = requireText(detail, "detail");
        if (outcome == PolicyOutcome.CONTINUE && !resumable) {
            throw new IllegalArgumentException("CONTINUE decisions must be resumable");
        }
        if ((outcome == PolicyOutcome.SUCCESS || outcome == PolicyOutcome.CANCELLED) && resumable) {
            throw new IllegalArgumentException(outcome + " decisions must not be resumable");
        }
    }

    public static PolicyDecision continueExecution(String code, String detail) {
        return new PolicyDecision(PolicyOutcome.CONTINUE, code, detail, true);
    }

    public static PolicyDecision success(String code, String detail) {
        return new PolicyDecision(PolicyOutcome.SUCCESS, code, detail, false);
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
