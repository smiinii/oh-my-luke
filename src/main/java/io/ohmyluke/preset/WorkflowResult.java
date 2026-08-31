package io.ohmyluke.preset;

import io.ohmyluke.state.ApprovalState;

public record WorkflowResult(String runId, WorkflowStatus status, String reason, int attempts,
                             long recordedUsage, boolean allTokenUsageAvailable, ApprovalState approval) {
    public int exitCode() { return status == WorkflowStatus.SUCCEEDED ? 0 : status == WorkflowStatus.WAITING_APPROVAL ? 3 : 1; }
}
