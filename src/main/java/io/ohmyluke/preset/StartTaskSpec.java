package io.ohmyluke.preset;

/** A new single-file task declares its constraints before an execution structure is selected. */
public record StartTaskSpec(String goal, String file, int maxAttempts, long maxUsage, long maxElapsedMillis,
                            int maxRepeatedFailures, ValidationSpec validation, String model, String reasoning,
                            boolean approvalBeforeApply) {
    public StartTaskSpec {
        // Reuse the existing immutable task boundary without requiring an input mode.
        TaskSpec validated = new TaskSpec(1, goal, file, ExecutionMode.LOOP, maxAttempts, maxUsage,
                maxElapsedMillis, maxRepeatedFailures, validation, model, reasoning);
        reasoning = validated.reasoning();
    }

    public TaskSpec toTask(ExecutionMode mode) {
        return new TaskSpec(1, goal, file, mode, mode == ExecutionMode.DIRECT ? 1 : maxAttempts,
                maxUsage, maxElapsedMillis, maxRepeatedFailures, validation, model, reasoning);
    }

    public StartTaskSpec withRuntimeSelection(String modelOverride, String reasoningOverride) {
        return new StartTaskSpec(goal, file, maxAttempts, maxUsage, maxElapsedMillis, maxRepeatedFailures,
                validation, modelOverride == null ? model : modelOverride,
                reasoningOverride == null ? reasoning : reasoningOverride, approvalBeforeApply);
    }
}
