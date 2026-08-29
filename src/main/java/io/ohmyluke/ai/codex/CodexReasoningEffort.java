package io.ohmyluke.ai.codex;

/** Values documented for Codex model_reasoning_effort. */
public enum CodexReasoningEffort {
    MINIMAL("minimal"),
    LOW("low"),
    MEDIUM("medium"),
    HIGH("high"),
    XHIGH("xhigh");

    private final String configValue;

    CodexReasoningEffort(String configValue) {
        this.configValue = configValue;
    }

    public String configValue() {
        return configValue;
    }
}
