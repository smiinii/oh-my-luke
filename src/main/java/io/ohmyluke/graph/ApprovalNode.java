package io.ohmyluke.graph;

import java.util.Objects;

/** A fixed human decision boundary. Ordinary node execution must never imply consent. */
public record ApprovalNode(NodeId id, String prompt) implements Node {
    public ApprovalNode {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(prompt, "prompt");
        if (prompt.isBlank() || prompt.length() > 4096 || prompt.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("approval prompt must be bounded printable text");
        }
    }

    @Override public String fingerprint() { return "approval:v1:" + prompt.length() + ":" + prompt; }

    @Override public NodeResult execute(NodeContext context) {
        throw new GraphExecutionException("human approval must be explicitly resolved: " + id);
    }
}
