package io.ohmyluke.state;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.ohmyluke.policy.PolicyConfiguration;
import io.ohmyluke.policy.PolicyState;
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
        if (checkpoint.schemaVersion() != RunCheckpoint.CURRENT_SCHEMA_VERSION) {
            throw new UnsupportedCheckpointVersionException(checkpoint.schemaVersion());
        }
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
            if (root == null || !root.isObject()) {
                throw new CheckpointException("checkpoint must be a JSON object");
            }
            JsonNode versionNode = root.get("schemaVersion");
            if (versionNode == null || !versionNode.canConvertToInt()) {
                throw new CheckpointException("checkpoint schemaVersion is missing or invalid");
            }
            int version = versionNode.intValue();
            if (version == 1) {
                ObjectNode migrated = (ObjectNode) root.deepCopy();
                migrated.put("schemaVersion", 2);
                migrated.set("policyConfiguration", mapper.valueToTree(PolicyConfiguration.unlimited()));
                migrated.set("policyState", mapper.valueToTree(PolicyState.initial(0)));
                root = migrated;
                version = 2;
            }
            if (version == 2) {
                ObjectNode migrated = (ObjectNode) root.deepCopy();
                migrated.put("schemaVersion", RunCheckpoint.CURRENT_SCHEMA_VERSION);
                migrated.putNull("approval");
                root = migrated;
                version = RunCheckpoint.CURRENT_SCHEMA_VERSION;
            }
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
