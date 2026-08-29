package io.ohmyluke.ai;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

final class AiFingerprints {
    private AiFingerprints() {}

    static String fakeRuntime(List<FakeAiExchange> exchanges) {
        StringBuilder encoded = new StringBuilder();
        append(encoded, Integer.toString(exchanges.size()));
        for (FakeAiExchange exchange : exchanges.stream()
                .sorted(java.util.Comparator.comparing(
                        item -> item.expectedRequest().invocationId()))
                .toList()) {
            appendRequest(encoded, exchange.expectedRequest());
            appendResult(encoded, exchange.result());
        }
        return sha256(encoded.toString());
    }

    static String node(
            String runtimeFingerprint,
            String instruction,
            List<String> inputStateKeys,
            String outputStateKey) {
        StringBuilder encoded = new StringBuilder();
        append(encoded, runtimeFingerprint);
        append(encoded, instruction);
        append(encoded, Integer.toString(inputStateKeys.size()));
        inputStateKeys.forEach(value -> append(encoded, value));
        append(encoded, outputStateKey);
        return sha256(encoded.toString());
    }

    static String invocation(String runId, String nodeId, int executedSteps) {
        StringBuilder encoded = new StringBuilder();
        append(encoded, runId);
        append(encoded, nodeId);
        append(encoded, Integer.toString(executedSteps));
        return sha256(encoded.toString());
    }

    private static void appendRequest(StringBuilder target, AiRequest request) {
        append(target, request.invocationId());
        append(target, request.instruction());
        append(target, Integer.toString(request.context().size()));
        for (Map.Entry<String, String> entry : request.context().entrySet()) {
            append(target, entry.getKey());
            append(target, entry.getValue());
        }
    }

    private static void appendResult(StringBuilder target, AiRuntimeResult result) {
        append(target, result.status().name());
        append(target, result.output());
        append(target, Long.toString(result.usage()));
        if (result.failure() == null) {
            append(target, "none");
        } else {
            append(target, result.failure().code().name());
        }
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
