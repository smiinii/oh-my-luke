package io.ohmyluke.preset;

import java.util.Objects;

/** Fixed, non-sensitive selection provenance stored with the effective run contract. */
public record RunSelection(int ruleVersion, Strategy strategy, Mode mode, String reasonCode) {
    public static final String STATE_KEY = "execution.selection";
    public enum Strategy { AUTO, MANUAL }
    public enum Mode { DIRECT, LOOP, WORKFLOW }

    public RunSelection {
        if (ruleVersion != 1) { throw new IllegalArgumentException("unsupported selection rule version"); }
        Objects.requireNonNull(strategy, "strategy");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(reasonCode, "reasonCode");
        boolean valid = switch (reasonCode) {
            case "auto-workflow-declared", "auto-approval-required" -> strategy == Strategy.AUTO && mode == Mode.WORKFLOW;
            case "auto-single-attempt" -> strategy == Strategy.AUTO && mode == Mode.DIRECT;
            case "auto-bounded-retry" -> strategy == Strategy.AUTO && mode == Mode.LOOP;
            case "manual-direct" -> strategy == Strategy.MANUAL && mode == Mode.DIRECT;
            case "manual-loop" -> strategy == Strategy.MANUAL && mode == Mode.LOOP;
            case "manual-workflow" -> strategy == Strategy.MANUAL && mode == Mode.WORKFLOW;
            default -> false;
        };
        if (!valid) { throw new IllegalArgumentException("invalid fixed selection reason"); }
    }
}
