package io.ohmyluke.ai.codex;

import io.ohmyluke.ai.AiTokenUsage;

record CodexParsedOutput(
        boolean completed,
        boolean failed,
        String finalMessage,
        String threadId,
        AiTokenUsage tokenUsage) {}
