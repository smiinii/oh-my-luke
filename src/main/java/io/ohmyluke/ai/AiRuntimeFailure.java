package io.ohmyluke.ai;

import java.util.Objects;

/** Stable, non-secret failure identity returned by an AI runtime adapter. */
public record AiRuntimeFailure(AiFailureCode code) {
    public AiRuntimeFailure {
        Objects.requireNonNull(code, "code");
    }

    public String publicCause() {
        return code.publicMessage();
    }
}
