package io.ohmyluke.policy;

import java.util.Locale;
import java.util.Objects;

/** Deterministic identity of a failure, excluding volatile message details. */
public record FailureFingerprint(
        String type,
        String code,
        String node,
        String normalizedCause) {
    public FailureFingerprint {
        type = requireText(type, "type");
        code = requireText(code, "code");
        node = requireText(node, "node");
        normalizedCause = requireText(normalizedCause, "normalizedCause");
    }

    public static FailureFingerprint normalized(
            String type,
            String code,
            String node,
            String cause) {
        return new FailureFingerprint(
                normalize(type),
                normalize(code),
                normalize(node),
                normalize(cause));
    }

    private static String normalize(String value) {
        return requireText(value, "fingerprint value")
                .trim()
                .replaceAll("\\s+", " ")
                .toLowerCase(Locale.ROOT);
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
