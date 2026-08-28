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
        type = normalizeInsensitive(type, "type");
        code = normalizeInsensitive(code, "code");
        node = normalizeNode(node);
        normalizedCause = normalizeInsensitive(normalizedCause, "normalizedCause");
    }

    public static FailureFingerprint normalized(
            String type,
            String code,
            String node,
            String cause) {
        return new FailureFingerprint(type, code, node, cause);
    }

    private static String normalizeInsensitive(String value, String name) {
        return requireText(value, name)
                .trim()
                .replaceAll("\\s+", " ")
                .toLowerCase(Locale.ROOT);
    }

    private static String normalizeNode(String value) {
        return requireText(value, "node").trim();
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
