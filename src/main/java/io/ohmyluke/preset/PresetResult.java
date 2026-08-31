package io.ohmyluke.preset;

public record PresetResult(String runId, PresetStatus status, String reason, int attempts,
                           long recordedUsage, boolean allTokenUsageAvailable) {
    public int exitCode() {
        return status == PresetStatus.SUCCEEDED ? 0 : 1;
    }
}
