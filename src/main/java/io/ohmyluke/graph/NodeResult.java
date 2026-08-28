package io.ohmyluke.graph;

import java.util.Objects;

/** Outcome and state changes produced by one node execution. */
public record NodeResult(Outcome outcome, StatePatch statePatch) {
    public NodeResult {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(statePatch, "statePatch");
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
}
