package io.ohmyluke.ai.codex;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.ohmyluke.ai.AiTokenUsage;
import io.ohmyluke.usage.UsageFiles;
import io.ohmyluke.usage.UsageReport;
import java.nio.file.Path;

/** Reads only known invocation IDs. Does not scan another CLI's sessions or expose stored response text. */
public final class CodexUsageReader implements UsageReport.Source {
    private static final JsonMapper JSON = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final Path root;
    public CodexUsageReader(Path root) {
        try { this.root=root.toRealPath(); } catch(java.io.IOException error) { throw new IllegalArgumentException("작업 폴더를 확인하세요."); }
    }
    @Override public UsageReport.Evidence read(String id) {
        try {
            Path file = root.resolve(".oml/runtime/codex/invocations/" + CodexHashing.safeFileId(id) + ".json");
            JsonNode stored = JSON.readTree(UsageFiles.read(root, file, 8 * 1024 * 1024));
            if (number(stored, "schemaVersion") != 1
                    || !text(stored,"requestFingerprint").matches("[a-f0-9]{64}")
                    || !text(stored,"runtimeFingerprint").matches("codex-cli:v1:sha256:[a-f0-9]{64}")) { return null; }
            JsonNode result = stored.path("result"), usage = result.path("tokenUsage");
            String status = text(result, "status");
            if (!status.equals("SUCCESS") && !status.equals("FAILURE")) { return null; }
            if (!usage.path("available").isBoolean()) { return null; }
            var tokens = new AiTokenUsage(usage.get("available").booleanValue(), number(usage,"inputTokens"),
                    number(usage,"cachedInputTokens"), number(usage,"outputTokens"), number(usage,"reasoningOutputTokens"), text(usage,"source"));
            if (tokens.available() && (!tokens.source().equals("codex-exec-jsonl")
                    || Math.addExact(tokens.inputTokens(), tokens.outputTokens()) != number(result,"usage"))) { return null; }
            return new UsageReport.Evidence(tokens, text(result,"runtimeSessionId"), status);
        } catch (Exception failure) { return null; } // Raw response, account IDs and parser excerpts never reach the UI.
    }
    private static long number(JsonNode node, String key) {
        JsonNode value = node.path(key);
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0) { throw new IllegalArgumentException(); }
        return value.longValue();
    }
    private static String text(JsonNode node, String key) {
        if (!node.path(key).isTextual()) { throw new IllegalArgumentException(); }
        return node.get(key).textValue();
    }
}
