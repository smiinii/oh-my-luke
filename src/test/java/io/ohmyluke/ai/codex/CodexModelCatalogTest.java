package io.ohmyluke.ai.codex;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CodexModelCatalogTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test void protocolUsesOnlyMetadataRequestsAndFollowsPagesUsingActualModelIds() throws Exception {
        List<String> requests = new ArrayList<>();
        var catalog = CodexModelCatalog.read((method, params) -> {
            requests.add(method);
            return switch (method) {
                case "initialize" -> tree("{}");
                case "initialized" -> null;
                case "account/read" -> { assertEquals(false, params.get("refreshToken")); yield tree("{\"account\":{\"type\":\"chatgpt\",\"email\":\"private@example.test\"}}"); }
                case "model/list" -> {
                    assertEquals(false, params.get("includeHidden"));
                    yield params.containsKey("cursor") ? tree("{\"data\":[" + model("wire-b", "actual-b", false) + "],\"nextCursor\":null}")
                            : tree("{\"data\":[" + model("wire-a", "actual-a", false) + "," + model("hidden", "secret-model", true) + "],\"nextCursor\":\"page2\"}");
                }
                default -> throw new AssertionError("Unexpected request " + method);
            };
        });
        assertEquals(List.of("initialize", "initialized", "account/read", "model/list", "model/list"), requests);
        assertEquals(List.of("actual-a", "actual-b"), catalog.models().stream().map(CodexModelCatalog.Model::model).toList());
        assertFalse(catalog.toString().contains("private@example.test"));
    }

    @Test void loggedOutAndApiKeyAccountsDoNotReturnAnAssumedSubscriptionCatalog() {
        for (String account : List.of("null", "{\"type\":\"apiKey\"}", "{\"type\":\"amazonBedrock\"}")) {
            var result = CodexModelCatalog.read((method, params) -> switch (method) {
                case "initialize" -> tree("{}");
                case "initialized" -> null;
                case "account/read" -> tree("{\"account\":" + account + "}");
                default -> throw new AssertionError("must not fetch models");
            });
            assertTrue(result.models().isEmpty());
            assertNotEquals(CodexModelCatalog.Status.AVAILABLE, result.status());
        }
    }

    @Test void malformedAndIncompletePagesAreNotPresentedAsAUsablePartialCatalog() {
        for (String page : List.of("{}", "{\"data\":[]}", "{\"data\":[],\"nextCursor\":3}",
                "{\"data\":[{}],\"nextCursor\":null}",
                "{\"data\":[" + model("a", "a", false) + "," + model("a", "a", false) + "],\"nextCursor\":null}",
                "{\"data\":[" + model("a", "a", false) + "],\"nextCursor\":\"loop\"}")) {
            var result = CodexModelCatalog.read((method, params) -> metadata(method, page));
            assertEquals(CodexModelCatalog.Status.UNAVAILABLE, result.status(), page);
            assertTrue(result.models().isEmpty());
        }
    }

    private JsonNode metadata(String method, String page) {
        return switch (method) {
            case "initialize" -> tree("{}");
            case "initialized" -> null;
            case "account/read" -> tree("{\"account\":{\"type\":\"chatgpt\"}}");
            default -> tree(page);
        };
    }
    private JsonNode tree(String input) { try { return json.readTree(input); } catch (Exception error) { throw new AssertionError(error); } }
    private String model(String id, String model, boolean hidden) {
        return "{\"id\":\"" + id + "\",\"model\":\"" + model + "\",\"displayName\":\"Example\",\"hidden\":" + hidden + ",\"isDefault\":false}";
    }
}
