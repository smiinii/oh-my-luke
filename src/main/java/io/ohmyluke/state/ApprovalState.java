package io.ohmyluke.state;

import io.ohmyluke.graph.NodeId;
import java.util.Objects;

/** Durable consent bound to one exact graph visit and its input state. */
public record ApprovalState(String requestId, NodeId node, String prompt, ApprovalDecision decision) {
    public ApprovalState {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(node, "node");
        Objects.requireNonNull(prompt, "prompt");
        Objects.requireNonNull(decision, "decision");
        if (!requestId.matches("[0-9a-f]{64}") || prompt.isBlank() || prompt.length() > 4096
                || prompt.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("invalid approval state");
        }
    }

    public ApprovalState withDecision(ApprovalDecision selected) {
        return new ApprovalState(requestId, node, prompt, selected);
    }
}
