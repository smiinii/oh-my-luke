package io.ohmyluke.ai.codex;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.ohmyluke.ai.AiTokenUsage;

final class CodexCliJsonParser {
    private static final String USAGE_SOURCE = "codex-exec-jsonl";
    private final ObjectMapper mapper = new ObjectMapper();

    CodexParsedOutput parse(String jsonLines) {
        String finalMessage = "";
        String threadId = "";
        boolean completed = false;
        boolean failed = false;
        boolean usageComplete = true;
        boolean sawUsage = false;
        long inputTokens = 0;
        long cachedInputTokens = 0;
        long outputTokens = 0;
        long reasoningOutputTokens = 0;

        String[] lines = jsonLines.split("\\R", -1);
        for (String line : lines) {
            if (line.isBlank()) {
                continue;
            }
            JsonNode event = parseObject(line);
            String type = requiredText(event, "type");
            switch (type) {
                case "thread.started" -> threadId = optionalText(event, "thread_id", threadId);
                case "item.completed" -> {
                    JsonNode item = event.get("item");
                    if (item != null
                            && item.isObject()
                            && "agent_message".equals(optionalText(item, "type", ""))) {
                        finalMessage = requiredText(item, "text");
                    }
                }
                case "turn.completed" -> {
                    completed = true;
                    JsonNode usage = event.get("usage");
                    TokenCounts counts = optionalTokenCounts(usage);
                    if (counts == null) {
                        usageComplete = false;
                    } else {
                        inputTokens = saturatedAdd(inputTokens, counts.inputTokens());
                        cachedInputTokens = saturatedAdd(
                                cachedInputTokens,
                                counts.cachedInputTokens());
                        outputTokens = saturatedAdd(outputTokens, counts.outputTokens());
                        reasoningOutputTokens = saturatedAdd(
                                reasoningOutputTokens,
                                counts.reasoningOutputTokens());
                        sawUsage = true;
                    }
                }
                case "turn.failed", "error" -> failed = true;
                default -> {
                    // Forward-compatible: unknown event types do not change the stable result fields.
                }
            }
        }
        AiTokenUsage usage = usageComplete && sawUsage
                ? AiTokenUsage.measured(
                        inputTokens,
                        cachedInputTokens,
                        outputTokens,
                        reasoningOutputTokens,
                        USAGE_SOURCE)
                : AiTokenUsage.unavailable();
        return new CodexParsedOutput(completed, failed, finalMessage, threadId, usage);
    }

    private JsonNode parseObject(String line) {
        try {
            JsonNode node = mapper.readTree(line);
            if (node == null || !node.isObject()) {
                throw new IllegalArgumentException("Codex JSONL event must be an object");
            }
            return node;
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("Codex JSONL event is invalid", error);
        }
    }

    private static String requiredText(JsonNode object, String field) {
        JsonNode value = object.get(field);
        if (value == null || !value.isTextual()) {
            throw new IllegalArgumentException("Codex JSONL text field is missing: " + field);
        }
        return value.textValue();
    }

    private static String optionalText(JsonNode object, String field, String fallback) {
        JsonNode value = object.get(field);
        return value != null && value.isTextual() ? value.textValue() : fallback;
    }

    private static TokenCounts optionalTokenCounts(JsonNode usage) {
        if (usage == null || !usage.isObject()) {
            return null;
        }
        Long input = optionalNonNegativeLong(usage, "input_tokens");
        Long cachedInput = optionalNonNegativeLong(usage, "cached_input_tokens");
        Long output = optionalNonNegativeLong(usage, "output_tokens");
        Long reasoningOutput = optionalNonNegativeLong(usage, "reasoning_output_tokens");
        if (input == null || cachedInput == null || output == null || reasoningOutput == null) {
            return null;
        }
        return new TokenCounts(input, cachedInput, output, reasoningOutput);
    }

    private static Long optionalNonNegativeLong(JsonNode object, String field) {
        JsonNode value = object.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
            return null;
        }
        long result = value.longValue();
        if (result < 0) {
            return null;
        }
        return result;
    }

    private static long saturatedAdd(long left, long right) {
        if (Long.MAX_VALUE - left < right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    private record TokenCounts(
            long inputTokens,
            long cachedInputTokens,
            long outputTokens,
            long reasoningOutputTokens) {}
}
