package io.ohmyluke.preset;

/** Approval waiting is neither failure nor successful completion. */
public enum WorkflowStatus { RUNNING, WAITING_APPROVAL, SUCCEEDED, VALIDATION_FAILED, LIMIT_REACHED, BLOCKED, CANCELLED }
