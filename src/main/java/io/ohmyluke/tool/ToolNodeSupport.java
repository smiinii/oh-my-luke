package io.ohmyluke.tool;

import io.ohmyluke.graph.ExecutionMetrics;
import io.ohmyluke.graph.FailureInfo;
import io.ohmyluke.graph.NodeResult;
import io.ohmyluke.graph.StatePatch;
import io.ohmyluke.policy.ToolPermission;
import io.ohmyluke.policy.ToolPermissionDecision;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

final class ToolNodeSupport {
    private ToolNodeSupport() {}

    static NodeResult result(
            String prefix,
            ToolPermissionDecision permission,
            boolean success,
            String failureType,
            String failureCode,
            String failureCause,
            Map<String, String> details) {
        LinkedHashMap<String, String> state = new LinkedHashMap<>();
        state.put(prefix + ".permission", permission.permission().name());
        state.put(prefix + ".reason", permission.reasonCode());
        state.put(prefix + ".success", Boolean.toString(success));
        state.putAll(details);
        StatePatch patch = new StatePatch(state);
        if (success) {
            return NodeResult.success(patch, ExecutionMetrics.oneToolCall());
        }
        String type = permission.permission() == ToolPermission.ASK ? "permission" : failureType;
        String code = permission.permission() == ToolPermission.ASK ? "approval-required" : failureCode;
        String cause = permission.permission() == ToolPermission.ASK ? permission.reasonCode() : failureCause;
        return NodeResult.failure(
                patch,
                new FailureInfo(type, code, cause),
                ExecutionMetrics.oneToolCall());
    }

    static String fingerprint(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by Java", impossible);
        }
    }

    static String processRequestFingerprint(ProcessToolRequest request) {
        StringBuilder encoded = new StringBuilder();
        append(encoded, request.operationId());
        try {
            append(encoded, request.executable().toRealPath().toString());
        } catch (java.io.IOException error) {
            throw new IllegalArgumentException("process executable must resolve before fingerprinting", error);
        }
        appendList(encoded, request.arguments());
        append(encoded, request.workingDirectory().normalize().toString());
        appendMap(encoded, request.environment());
        append(encoded, request.timeout().toString());
        append(encoded, Integer.toString(request.maxOutputBytes()));
        append(encoded, request.capability().name());
        return fingerprint(encoded.toString());
    }

    static String processPermissionTarget(ProcessToolRequest request, java.nio.file.Path realExecutable) {
        StringBuilder encoded = new StringBuilder();
        append(encoded, realExecutable.toString());
        appendList(encoded, request.arguments());
        append(encoded, request.workingDirectory().normalize().toString());
        appendMap(encoded, request.environment());
        String network = request.networkRequested() ? "network:any" : "network:none";
        append(encoded, network);
        return "process:"
                + request.capability().name().toLowerCase(java.util.Locale.ROOT)
                + ":"
                + network
                + ":sha256:"
                + fingerprint(encoded.toString());
    }

    private static void appendList(StringBuilder target, List<String> values) {
        target.append(values.size()).append(';');
        values.forEach(value -> append(target, value));
    }

    private static void appendMap(StringBuilder target, Map<String, String> values) {
        TreeMap<String, String> sorted = new TreeMap<>(values);
        target.append(sorted.size()).append(';');
        sorted.forEach((key, value) -> {
            append(target, key);
            append(target, value);
        });
    }

    private static void append(StringBuilder target, String value) {
        String nonNull = java.util.Objects.requireNonNull(value, "fingerprint value");
        target.append(nonNull.length()).append(':').append(nonNull).append(';');
    }
}
