package io.ohmyluke.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.ohmyluke.graph.NodeId;
import io.ohmyluke.graph.Outcome;
import io.ohmyluke.graph.RunState;
import io.ohmyluke.graph.RunStatus;
import io.ohmyluke.graph.StatePatch;
import io.ohmyluke.graph.TransitionEvent;
import io.ohmyluke.policy.PolicyConfiguration;
import io.ohmyluke.policy.PolicyDecision;
import io.ohmyluke.policy.PolicyOutcome;
import io.ohmyluke.policy.PolicyState;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CheckpointCodecTest {
    private final CheckpointCodec codec = new CheckpointCodec();

    @Test
    void roundTripsStateWithoutChangingOrder() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("first", "1");
        values.put("second", "2");
        StatePatch patch = new StatePatch(values);
        NodeId write = new NodeId("write");
        NodeId inspect = new NodeId("inspect");
        TransitionEvent event = new TransitionEvent(
                1,
                write,
                Outcome.SUCCESS,
                inspect,
                "always",
                patch,
                values);
        RunState state = new RunState(
                RunStatus.RUNNING,
                inspect,
                1,
                values,
                List.of(write, inspect),
                List.of(event));
        RunCheckpoint checkpoint = RunCheckpoint.current(
                "run-001",
                "graph-signature",
                CheckpointPhase.READY,
                state,
                new PolicyConfiguration(5, 60_000, 10, 2, 1_000, 3, 3),
                PolicyState.initial(1234).withCounters(2, 2, 1, 400).withDecision(
                        new PolicyDecision(PolicyOutcome.LIMIT_REACHED, "limit.usage", "usage reached", false)));

        RunCheckpoint restored = codec.decode(codec.encode(checkpoint));

        assertEquals(checkpoint, restored);
        assertEquals(List.of("first", "second"), List.copyOf(restored.state().values().keySet()));
        assertEquals(
                List.of("first", "second"),
                List.copyOf(restored.state().events().getFirst().stateAfter().keySet()));
    }

    @Test
    void migratesLegacyVersionOneWithSafePolicyDefaults() {
        String legacy = """
                {
                  "schemaVersion": 1,
                  "runId": "run-001",
                  "graphSignature": "signature",
                  "phase": "READY",
                  "state": {
                    "status": "RUNNING",
                    "currentNode": {"value": "work"},
                    "executedSteps": 0,
                    "values": {},
                    "path": [{"value": "work"}],
                    "events": []
                  }
                }
                """;

        RunCheckpoint migrated = codec.decode(legacy);

        assertEquals(RunCheckpoint.CURRENT_SCHEMA_VERSION, migrated.schemaVersion());
        assertEquals(PolicyConfiguration.unlimited(), migrated.policyConfiguration());
        assertEquals(0, migrated.policyState().iterations());
        assertEquals("policy.not-evaluated", migrated.policyState().lastDecision().reasonCode());
        assertNull(migrated.approval());
    }

    @Test
    void migratesVersionTwoWithoutLosingPolicyOrFabricatingConsent() throws Exception {
        NodeId node = new NodeId("work");
        RunCheckpoint current = RunCheckpoint.current("legacy", "signature", CheckpointPhase.READY,
                new RunState(RunStatus.RUNNING, node, 0, Map.of(), List.of(node), List.of()),
                new PolicyConfiguration(5, 60000, 10, 2, 1000, 3, 3), PolicyState.initial(1234));
        com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.node.ObjectNode legacy = (com.fasterxml.jackson.databind.node.ObjectNode)
                json.readTree(codec.encode(current));
        legacy.put("schemaVersion", 2);
        legacy.remove("approval");
        RunCheckpoint migrated = codec.decode(json.writeValueAsString(legacy));
        assertEquals(current, migrated);
        assertNull(migrated.approval());
    }

    @Test
    void roundTripsPendingAndDecidedApprovalInCurrentSchema() {
        NodeId node = new NodeId("gate");
        for (ApprovalDecision decision : ApprovalDecision.values()) {
            RunCheckpoint checkpoint = RunCheckpoint.current("approval", "signature", CheckpointPhase.READY,
                    new RunState(RunStatus.RUNNING, node, 0, Map.of(), List.of(node), List.of()),
                    PolicyConfiguration.unlimited(), PolicyState.initial(1),
                    new ApprovalState("a".repeat(64), node, "계속 진행할까요?", decision));
            assertEquals(checkpoint, codec.decode(codec.encode(checkpoint)));
        }
    }

    @Test
    void rejectsUnsupportedSchemaVersion() {
        String json = """
                {
                  "schemaVersion": 999,
                  "runId": "run-001",
                  "graphSignature": "signature",
                  "phase": "READY",
                  "state": {}
                }
                """;

        assertThrows(UnsupportedCheckpointVersionException.class, () -> codec.decode(json));
    }

    @Test
    void refusesToWriteAnUnsupportedSchemaVersion() {
        NodeId node = new NodeId("write");
        RunCheckpoint unsupported = new RunCheckpoint(
                999,
                "run-001",
                "graph-signature",
                CheckpointPhase.READY,
                new RunState(
                        RunStatus.RUNNING,
                        node,
                        0,
                        Map.of(),
                        List.of(node),
                        List.of()),
                PolicyConfiguration.unlimited(),
                PolicyState.initial(0));

        assertThrows(UnsupportedCheckpointVersionException.class, () -> codec.encode(unsupported));
    }
}
