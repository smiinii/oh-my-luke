package io.ohmyluke.graph;

import java.util.Objects;

/** Outcome, state changes, and optional stable failure identity produced by one node execution. */
public record NodeResult(
        Outcome outcome,
        StatePatch statePatch,
        FailureInfo failureInfo,
        ExecutionMetrics metrics) {
    public NodeResult {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(statePatch, "statePatch");
        metrics = metrics == null ? ExecutionMetrics.NONE : metrics;
        if (outcome != Outcome.FAILURE && failureInfo != null) {
            throw new IllegalArgumentException("failure identity is only valid for FAILURE outcomes");
        }
    }

    public NodeResult(Outcome outcome, StatePatch statePatch) {
        this(outcome, statePatch, null, ExecutionMetrics.NONE);
    }

    public NodeResult(Outcome outcome, StatePatch statePatch, FailureInfo failureInfo) {
        this(outcome, statePatch, failureInfo, ExecutionMetrics.NONE);
    }

    public static NodeResult success() {
        return success(StatePatch.empty());
    }

    public static NodeResult success(StatePatch patch) {
        return new NodeResult(Outcome.SUCCESS, patch, null, ExecutionMetrics.NONE);
    }

    public static NodeResult failure() {
        return failure(StatePatch.empty());
    }

    public static NodeResult failure(StatePatch patch) {
        return new NodeResult(Outcome.FAILURE, patch, null, ExecutionMetrics.NONE);
    }

    public static NodeResult failure(FailureInfo failure) {
        return failure(StatePatch.empty(), failure);
    }

    public static NodeResult failure(StatePatch patch, FailureInfo failure) {
        return new NodeResult(
                Outcome.FAILURE,
                patch,
                Objects.requireNonNull(failure, "failure"),
                ExecutionMetrics.NONE);
    }

    public static NodeResult success(StatePatch patch, ExecutionMetrics metrics) {
        return new NodeResult(Outcome.SUCCESS, patch, null, metrics);
    }

    public static NodeResult failure(
            StatePatch patch,
            FailureInfo failure,
            ExecutionMetrics metrics) {
        return new NodeResult(Outcome.FAILURE, patch, Objects.requireNonNull(failure, "failure"), metrics);
    }
}
