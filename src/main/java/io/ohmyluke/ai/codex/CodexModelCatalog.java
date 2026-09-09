package io.ohmyluke.ai.codex;

import com.fasterxml.jackson.databind.JsonNode;
import io.ohmyluke.profile.ExecutionProfile;
import io.ohmyluke.tool.SecretRedactor;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/** Metadata only. No thread/turn, login, token refresh, configuration write, or account identifiers in the result. */
public record CodexModelCatalog(Status status, List<Model> models) {
    public enum Status { AVAILABLE, LOGIN_REQUIRED, UNSUPPORTED_AUTH, UNAVAILABLE }
    public record Model(String model, String label, boolean recommended) {
        public Model {
            new ExecutionProfile("codex", "oml", model);
            if (model == null || label == null || label.length() > 256) { throw new IllegalArgumentException("invalid catalog model"); }
            label = new SecretRedactor().redact(label, false).codePoints().collect(StringBuilder::new,
                    (text, ch) -> text.appendCodePoint(Character.isISOControl(ch) || Character.getType(ch) == Character.FORMAT ? '?' : ch),
                    StringBuilder::append).toString();
        }
    }
    public CodexModelCatalog {
        models = List.copyOf(models);
        if (status == null || (status != Status.AVAILABLE && !models.isEmpty())) { throw new IllegalArgumentException("invalid catalog state"); }
    }
    @FunctionalInterface public interface Exchange {
        JsonNode call(String method, Map<String, Object> params) throws Exception;
    }

    public static CodexModelCatalog unavailable() { return new CodexModelCatalog(Status.UNAVAILABLE, List.of()); }

    public static CodexModelCatalog read(Exchange exchange) {
        try {
            JsonNode initialized = exchange.call("initialize", Map.of("clientInfo", Map.of(
                    "name", "oh_my_luke", "title", "Oh My Luke", "version", "0.1.0")));
            if (initialized == null || !initialized.isObject()) { return unavailable(); }
            exchange.call("initialized", Map.of());
            JsonNode account = exchange.call("account/read", Map.of("refreshToken", false));
            if (account == null || !account.isObject() || !account.has("account")) { return unavailable(); }
            if (account.get("account").isNull()) { return new CodexModelCatalog(Status.LOGIN_REQUIRED, List.of()); }
            if (!"chatgpt".equals(account.path("account").path("type").asText())) {
                return new CodexModelCatalog(Status.UNSUPPORTED_AUTH, List.of());
            }
            List<Model> models = new ArrayList<>();
            var cursors = new HashSet<String>();
            var ids = new HashSet<String>();
            String cursor = null;
            for (int page = 0; page < 5; page++) {
                var params = new java.util.HashMap<String, Object>();
                params.put("limit", 20); params.put("includeHidden", false);
                if (cursor != null) { params.put("cursor", cursor); }
                JsonNode result = exchange.call("model/list", params);
                if (result == null || !result.path("data").isArray() || result.path("data").size() > 100
                        || !result.has("nextCursor")) { return unavailable(); }
                for (JsonNode item : result.get("data")) {
                    if (!item.path("model").isTextual() || !item.path("displayName").isTextual()
                            || !item.path("hidden").isBoolean() || !item.path("isDefault").isBoolean()
                            || !ids.add(item.get("model").textValue()) || ids.size() > 100) { return unavailable(); }
                    Model model = new Model(item.get("model").textValue(), item.get("displayName").textValue(), item.get("isDefault").booleanValue());
                    if (!item.get("hidden").booleanValue()) { models.add(model); }
                }
                JsonNode next = result.get("nextCursor");
                if (next.isNull()) { return new CodexModelCatalog(Status.AVAILABLE, models); }
                if (!next.isTextual() || next.textValue().isBlank() || next.textValue().length() > 1024
                        || !cursors.add(next.textValue())) { return unavailable(); }
                cursor = next.textValue();
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        } catch (Exception ignored) {
            // Do not expose response text: errors and account replies may contain private data.
        }
        return unavailable();
    }
}
