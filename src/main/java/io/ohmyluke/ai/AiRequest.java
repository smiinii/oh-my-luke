package io.ohmyluke.ai;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Immutable, provider-neutral request containing only explicitly selected graph context. */
public record AiRequest(
        String invocationId,
        String instruction,
        Map<String, String> context) {
    public AiRequest {
        invocationId = requireText(invocationId, "invocationId");
        instruction = requireText(instruction, "instruction");
        Objects.requireNonNull(context, "context");
        TreeMap<String, String> sorted = new TreeMap<>();
        context.forEach((key, value) -> sorted.put(
                requireText(key, "context key"),
                Objects.requireNonNull(value, "context value")));
        context = Collections.unmodifiableMap(sorted);
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
