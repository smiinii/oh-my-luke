package io.ohmyluke.policy;

/** Objective result of evaluating run policy. */
public enum PolicyOutcome {
    CONTINUE,
    SUCCESS,
    LIMIT_REACHED,
    BLOCKED,
    CANCELLED
}
