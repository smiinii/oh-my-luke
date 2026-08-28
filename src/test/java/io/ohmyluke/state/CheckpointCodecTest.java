package io.ohmyluke.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
                        new PolicyDecision(PolicyOutcome.LIMIT_REACHED, "limit.usage", "usage reached", true)));

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
