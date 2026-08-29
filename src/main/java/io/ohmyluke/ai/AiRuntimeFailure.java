package io.ohmyluke.ai;

import java.util.Objects;
import java.util.regex.Pattern;

/** Stable, non-secret failure identity returned by an AI runtime adapter. */
public record AiRuntimeFailure(String code, AiFailureReason reason) {
    private static final Pattern SAFE_CODE = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");

    public AiRuntimeFailure {
        Objects.requireNonNull(code, "code");
        if (!SAFE_CODE.matcher(code).matches()) {
            throw new IllegalArgumentException("code must be a stable safe identifier");
        }
        Objects.requireNonNull(reason, "reason");
    }

    public String publicCause() {
        return reason.publicMessage();
    }
}
