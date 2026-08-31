package io.ohmyluke.ai.codex;

record CodexProcessResult(
        boolean started,
        boolean timedOut,
        int exitCode,
        String stdout,
        String stderr,
        boolean outputLimitExceeded,
        boolean inputWriteFailed) {}
