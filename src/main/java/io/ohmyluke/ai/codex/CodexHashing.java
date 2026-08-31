package io.ohmyluke.ai.codex;

import io.ohmyluke.ai.AiRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

final class CodexHashing {
    private CodexHashing() {}

    static String request(AiRequest request) {
        StringBuilder encoded = new StringBuilder();
        append(encoded, request.invocationId());
        append(encoded, request.instruction());
        append(encoded, Integer.toString(request.context().size()));
        for (Map.Entry<String, String> entry : request.context().entrySet()) {
            append(encoded, entry.getKey());
            append(encoded, entry.getValue());
        }
        return sha256(encoded.toString());
    }

    static String configuration(CodexCliConfiguration configuration) {
        StringBuilder encoded = new StringBuilder();
        append(encoded, "codex-cli-runtime-v1");
        append(encoded, configuration.executable());
        append(encoded, configuration.modelSelection().explicitModel().orElse("inherit"));
        append(encoded, configuration.reasoningSelection().explicitEffort()
                .map(CodexReasoningEffort::configValue)
                .orElse("inherit"));
        append(encoded, "read-only");
        append(encoded, Long.toString(configuration.timeout().toMillis()));
        append(encoded, Integer.toString(configuration.maxInputBytes()));
        append(encoded, Integer.toString(configuration.maxOutputBytes()));
        return sha256(encoded.toString());
    }

    static String safeFileId(String value) {
        return sha256(value);
    }

    private static void append(StringBuilder target, String value) {
        target.append(value.length()).append(':').append(value).append(';');
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by Java", impossible);
        }
    }
}
