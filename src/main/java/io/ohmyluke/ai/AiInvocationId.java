package io.ohmyluke.ai;

import io.ohmyluke.graph.NodeId;
import java.util.Objects;

/** Creates an opaque, stable idempotency identity for one logical AI node invocation. */
public final class AiInvocationId {
    private AiInvocationId() {}

    public static String forNode(String runId, NodeId nodeId, int executedSteps) {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(nodeId, "nodeId");
        if (runId.isBlank()) {
            throw new IllegalArgumentException("runId must not be blank");
        }
        if (executedSteps < 0) {
            throw new IllegalArgumentException("executedSteps must not be negative");
        }
        return "ai-call:v1:sha256:" + AiFingerprints.invocation(runId, nodeId.value(), executedSteps);
    }
}
