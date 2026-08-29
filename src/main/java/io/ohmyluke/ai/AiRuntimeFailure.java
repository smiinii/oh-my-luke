package io.ohmyluke.ai;

import java.util.Objects;

/** Stable, non-secret failure identity returned by an AI runtime adapter. */
public record AiRuntimeFailure(String code, String cause) {
    public AiRuntimeFailure {
        code = requireText(code, "code");
        cause = requireText(cause, "cause");
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
