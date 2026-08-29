package io.ohmyluke.ai.codex;

import io.ohmyluke.ai.AiRuntimeResult;
import java.util.Objects;

record CodexStoredInvocation(
        int schemaVersion,
        String requestFingerprint,
        String runtimeFingerprint,
        AiRuntimeResult result) {
    static final int CURRENT_SCHEMA_VERSION = 1;

    CodexStoredInvocation {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported Codex invocation schema: " + schemaVersion);
        }
        requestFingerprint = requireText(requestFingerprint, "requestFingerprint");
        runtimeFingerprint = requireText(runtimeFingerprint, "runtimeFingerprint");
        Objects.requireNonNull(result, "result");
    }

    static CodexStoredInvocation current(
            String requestFingerprint,
            String runtimeFingerprint,
            AiRuntimeResult result) {
        return new CodexStoredInvocation(
                CURRENT_SCHEMA_VERSION,
                requestFingerprint,
                runtimeFingerprint,
                result);
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
