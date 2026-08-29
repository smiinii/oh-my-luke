package io.ohmyluke.ai;

import java.util.Objects;

/** Structured AI result with generic usage units that policy limits can count. */
public record AiRuntimeResult(
        AiRuntimeStatus status,
        String output,
        AiRuntimeFailure failure,
        long usage,
        AiTokenUsage tokenUsage,
        String runtimeSessionId) {
    public AiRuntimeResult(
            AiRuntimeStatus status,
            String output,
            AiRuntimeFailure failure,
            long usage) {
        this(status, output, failure, usage, AiTokenUsage.unavailable(), "");
    }

    public AiRuntimeResult {
        Objects.requireNonNull(status, "status");
        output = Objects.requireNonNull(output, "output");
        tokenUsage = Objects.requireNonNull(tokenUsage, "tokenUsage");
        runtimeSessionId = Objects.requireNonNull(runtimeSessionId, "runtimeSessionId");
        if (runtimeSessionId.length() > 256
                || runtimeSessionId.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("runtimeSessionId is invalid");
        }
        if (usage < 0) {
            throw new IllegalArgumentException("usage must not be negative");
        }
        if (tokenUsage.available() && tokenUsage.recordedTotal() != usage) {
            throw new IllegalArgumentException("measured token total must match usage");
        }
        if (status == AiRuntimeStatus.SUCCESS && failure != null) {
            throw new IllegalArgumentException("successful result must not contain a failure");
        }
        if (status == AiRuntimeStatus.FAILURE && failure == null) {
            throw new IllegalArgumentException("failed result must contain a failure");
        }
        if (status == AiRuntimeStatus.FAILURE && !output.isEmpty()) {
            throw new IllegalArgumentException("failed result must not contain output");
        }
    }

    public static AiRuntimeResult success(String output, long usage) {
        return new AiRuntimeResult(
                AiRuntimeStatus.SUCCESS,
                output,
                null,
                usage,
                AiTokenUsage.unavailable(),
                "");
    }

    public static AiRuntimeResult success(String output, AiTokenUsage tokenUsage) {
        Objects.requireNonNull(tokenUsage, "tokenUsage");
        if (!tokenUsage.available()) {
            throw new IllegalArgumentException("measured token usage must be available");
        }
        return new AiRuntimeResult(
                AiRuntimeStatus.SUCCESS,
                output,
                null,
                tokenUsage.recordedTotal(),
                tokenUsage,
                "");
    }

    public static AiRuntimeResult success(
            String output,
            AiTokenUsage tokenUsage,
            String runtimeSessionId) {
        Objects.requireNonNull(tokenUsage, "tokenUsage");
        if (!tokenUsage.available()) {
            throw new IllegalArgumentException("measured token usage must be available");
        }
        return new AiRuntimeResult(
                AiRuntimeStatus.SUCCESS,
                output,
                null,
                tokenUsage.recordedTotal(),
                tokenUsage,
                runtimeSessionId);
    }

    public static AiRuntimeResult failure(AiFailureCode code, long usage) {
        return new AiRuntimeResult(
                AiRuntimeStatus.FAILURE,
                "",
                new AiRuntimeFailure(code),
                usage,
                AiTokenUsage.unavailable(),
                "");
    }

    public static AiRuntimeResult failure(AiFailureCode code, AiTokenUsage tokenUsage) {
        Objects.requireNonNull(tokenUsage, "tokenUsage");
        if (!tokenUsage.available()) {
            throw new IllegalArgumentException("measured token usage must be available");
        }
        return new AiRuntimeResult(
                AiRuntimeStatus.FAILURE,
                "",
                new AiRuntimeFailure(code),
                tokenUsage.recordedTotal(),
                tokenUsage,
                "");
    }

    public static AiRuntimeResult failure(
            AiFailureCode code,
            AiTokenUsage tokenUsage,
            String runtimeSessionId) {
        Objects.requireNonNull(tokenUsage, "tokenUsage");
        if (!tokenUsage.available()) {
            throw new IllegalArgumentException("measured token usage must be available");
        }
        return new AiRuntimeResult(
                AiRuntimeStatus.FAILURE,
                "",
                new AiRuntimeFailure(code),
                tokenUsage.recordedTotal(),
                tokenUsage,
                runtimeSessionId);
    }
}
