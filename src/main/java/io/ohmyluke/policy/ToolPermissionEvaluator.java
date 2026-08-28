package io.ohmyluke.policy;

/** Trusted boundary used by tools to obtain one ALLOW, ASK, or DENY decision. */
@FunctionalInterface
public interface ToolPermissionEvaluator {
    ToolPermissionDecision evaluate(ToolPermissionRequest request);
}
