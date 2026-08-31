package io.ohmyluke.preset;

/** Product outcome, deliberately distinct from the kernel's terminal-node COMPLETED status. */
public enum PresetStatus { RUNNING, SUCCEEDED, VALIDATION_FAILED, LIMIT_REACHED, BLOCKED, CANCELLED }
