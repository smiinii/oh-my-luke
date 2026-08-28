package io.ohmyluke.graph;

import java.util.Objects;

/** Stable, non-secret failure identity supplied by a node for repetition detection. */
public record FailureInfo(String type, String code, String cause) {
    public FailureInfo {
        type = requireText(type, "type");
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
