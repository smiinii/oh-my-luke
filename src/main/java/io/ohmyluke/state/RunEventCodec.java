package io.ohmyluke.state;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Objects;

/** Encodes one run event as exactly one JSONL line. */
public final class RunEventCodec {
    private final ObjectMapper mapper = new ObjectMapper();

    public String encode(RunEvent event) {
        Objects.requireNonNull(event, "event");
        if (event.schemaVersion() != RunEvent.CURRENT_SCHEMA_VERSION) {
            throw new UnsupportedCheckpointVersionException(event.schemaVersion());
        }
        try {
            return mapper.writeValueAsString(event);
        } catch (JsonProcessingException error) {
            throw new CheckpointException("failed to encode run event", error);
        }
    }

    public RunEvent decode(String json) {
        Objects.requireNonNull(json, "json");
        try {
            JsonNode root = mapper.readTree(json);
            if (root == null || !root.isObject()) {
                throw new CheckpointException("run event must be a JSON object");
            }
            JsonNode versionNode = root.get("schemaVersion");
            if (versionNode == null || !versionNode.canConvertToInt()) {
                throw new CheckpointException("run event schemaVersion is missing or invalid");
            }
            int version = versionNode.intValue();
            if (version != RunEvent.CURRENT_SCHEMA_VERSION) {
                throw new UnsupportedCheckpointVersionException(version);
            }
            return mapper.treeToValue(root, RunEvent.class);
        } catch (UnsupportedCheckpointVersionException error) {
            throw error;
        } catch (CheckpointException error) {
            throw error;
        } catch (JsonProcessingException | IllegalArgumentException error) {
            throw new CheckpointException("failed to decode run event", error);
        }
    }
}
