package io.ohmyluke.state;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.util.Objects;

/** Encodes and validates the versioned JSON checkpoint format. */
public final class CheckpointCodec {
    private final ObjectMapper mapper;

    public CheckpointCodec() {
        mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    }

    public String encode(RunCheckpoint checkpoint) {
        Objects.requireNonNull(checkpoint, "checkpoint");
        try {
            return mapper.writeValueAsString(checkpoint);
        } catch (JsonProcessingException error) {
            throw new CheckpointException("failed to encode checkpoint", error);
        }
    }

    public RunCheckpoint decode(String json) {
        Objects.requireNonNull(json, "json");
        try {
            JsonNode root = mapper.readTree(json);
            JsonNode versionNode = root.get("schemaVersion");
            if (versionNode == null || !versionNode.canConvertToInt()) {
                throw new CheckpointException("checkpoint schemaVersion is missing or invalid");
            }
            int version = versionNode.intValue();
            if (version != RunCheckpoint.CURRENT_SCHEMA_VERSION) {
                throw new UnsupportedCheckpointVersionException(version);
            }
            return mapper.treeToValue(root, RunCheckpoint.class);
        } catch (UnsupportedCheckpointVersionException error) {
            throw error;
        } catch (CheckpointException error) {
            throw error;
        } catch (JsonProcessingException | IllegalArgumentException error) {
            throw new CheckpointException("failed to decode checkpoint", error);
        }
    }
}
