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
import java.util.Map;

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
}
