package io.ohmyluke.graph;

import java.util.Objects;

/** Outcome, state changes, and optional stable failure identity produced by one node execution. */
public record NodeResult(Outcome outcome, StatePatch statePatch, FailureInfo failureInfo) {
    public NodeResult {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(statePatch, "statePatch");
        if (outcome != Outcome.FAILURE && failureInfo != null) {
            throw new IllegalArgumentException("failure identity is only valid for FAILURE outcomes");
        }
    }

    public NodeResult(Outcome outcome, StatePatch statePatch) {
        this(outcome, statePatch, null);
    }

    public static NodeResult success() {
        return success(StatePatch.empty());
    }

    public static NodeResult success(StatePatch patch) {
        return new NodeResult(Outcome.SUCCESS, patch);
    }

    public static NodeResult failure() {
        return failure(StatePatch.empty());
    }

    public static NodeResult failure(StatePatch patch) {
        return new NodeResult(Outcome.FAILURE, patch);
    }

    public static NodeResult failure(FailureInfo failure) {
        return failure(StatePatch.empty(), failure);
    }

    public static NodeResult failure(StatePatch patch, FailureInfo failure) {
        return new NodeResult(
                Outcome.FAILURE,
                patch,
                Objects.requireNonNull(failure, "failure"));
    }
}
