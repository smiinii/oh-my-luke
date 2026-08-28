package io.ohmyluke.graph;

/** Resource use reported by one node without letting the node mutate policy counters directly. */
public record ExecutionMetrics(long toolCalls, long usage) {
    public static final ExecutionMetrics NONE = new ExecutionMetrics(0, 0);

    public ExecutionMetrics {
        if (toolCalls < 0 || usage < 0) {
            throw new IllegalArgumentException("execution metrics must not be negative");
        }
    }

    public static ExecutionMetrics oneToolCall() {
        return new ExecutionMetrics(1, 0);
    }
}
