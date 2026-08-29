package io.ohmyluke.ai;

import java.util.Objects;

/** Optional provider-reported token breakdown; cached and reasoning counts are informational subsets. */
public record AiTokenUsage(
        boolean available,
        long inputTokens,
        long cachedInputTokens,
        long outputTokens,
        long reasoningOutputTokens,
        String source) {
    private static final AiTokenUsage UNAVAILABLE = new AiTokenUsage(
            false, 0, 0, 0, 0, "unavailable");

    public AiTokenUsage {
        if (inputTokens < 0
                || cachedInputTokens < 0
                || outputTokens < 0
                || reasoningOutputTokens < 0) {
            throw new IllegalArgumentException("token usage must not be negative");
        }
        source = requireText(source, "source");
        if (!available && (inputTokens != 0
                || cachedInputTokens != 0
                || outputTokens != 0
                || reasoningOutputTokens != 0)) {
            throw new IllegalArgumentException("unavailable token usage must contain zero counts");
        }
        if (cachedInputTokens > inputTokens) {
            throw new IllegalArgumentException("cached input tokens must not exceed input tokens");
        }
        if (reasoningOutputTokens > outputTokens) {
            throw new IllegalArgumentException("reasoning output tokens must not exceed output tokens");
        }
    }

    public static AiTokenUsage unavailable() {
        return UNAVAILABLE;
    }

    public static AiTokenUsage measured(
            long inputTokens,
            long cachedInputTokens,
            long outputTokens,
            long reasoningOutputTokens,
            String source) {
        return new AiTokenUsage(
                true,
                inputTokens,
                cachedInputTokens,
                outputTokens,
                reasoningOutputTokens,
                source);
    }

    /** Recorded total excludes cached/reasoning subsets to avoid double counting. */
    public long recordedTotal() {
        if (Long.MAX_VALUE - inputTokens < outputTokens) {
            return Long.MAX_VALUE;
        }
        return inputTokens + outputTokens;
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
