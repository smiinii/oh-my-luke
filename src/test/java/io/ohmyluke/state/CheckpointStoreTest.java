package io.ohmyluke.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ohmyluke.graph.NodeId;
import io.ohmyluke.graph.RunState;
import io.ohmyluke.graph.RunStatus;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CheckpointStoreTest {
    @TempDir
    Path projectRoot;

    private final CheckpointCodec codec = new CheckpointCodec();

    @Test
    void savesAndLoadsCheckpointFromRunDirectory() {
        CheckpointStore store = new CheckpointStore(projectRoot, codec);
        RunCheckpoint checkpoint = checkpoint("run-001", 1);

        store.save(checkpoint);
        CheckpointLoadResult result = store.load("run-001");

        assertEquals(checkpoint, result.checkpoint());
        assertFalse(result.recoveredFromBackup());
        assertTrue(Files.exists(projectRoot.resolve(".oml/runs/run-001/state.json")));
        assertFalse(Files.exists(projectRoot.resolve(".oml/runs/run-001/state.json.tmp")));
    }

    @Test
    void recoversPreviousSafeCheckpointWhenLatestFileIsCorrupt() throws IOException {
        CheckpointStore store = new CheckpointStore(projectRoot, codec);
        RunCheckpoint previous = checkpoint("run-001", 1);
        RunCheckpoint latest = checkpoint("run-001", 2);
        store.save(previous);
        store.save(latest);
        Files.writeString(store.statePath("run-001"), "{broken-json");

        CheckpointLoadResult result = store.load("run-001");

        assertEquals(previous, result.checkpoint());
        assertTrue(result.recoveredFromBackup());
    }

    @Test
    void nodeStartedMarkerDoesNotReplaceTheLastReadyBackup() throws IOException {
        CheckpointStore store = new CheckpointStore(projectRoot, codec);
        RunCheckpoint safe = checkpoint("run-001", 1);
        RunCheckpoint executing = RunCheckpoint.current(
                safe.runId(),
                safe.graphSignature(),
                CheckpointPhase.NODE_STARTED,
                safe.state());
        RunCheckpoint latest = checkpoint("run-001", 2);
        store.save(safe);
        store.save(executing);
        store.save(latest);
        Files.writeString(store.statePath("run-001"), "{broken-json");

        CheckpointLoadResult result = store.load("run-001");

        assertEquals(safe, result.checkpoint());
        assertEquals(CheckpointPhase.READY, result.checkpoint().phase());
        assertTrue(result.recoveredFromBackup());
    }

    @Test
    void doesNotHideUnsupportedSchemaBehindBackupRecovery() throws IOException {
        CheckpointStore store = new CheckpointStore(projectRoot, codec);
        store.save(checkpoint("run-001", 1));
        store.save(checkpoint("run-001", 2));
        String unsupported = Files.readString(store.statePath("run-001"))
                .replace("\"schemaVersion\" : 1", "\"schemaVersion\" : 999");
        Files.writeString(store.statePath("run-001"), unsupported);

        assertThrows(
                UnsupportedCheckpointVersionException.class,
                () -> store.load("run-001"));
    }

    @Test
    void rejectsRunIdThatCouldEscapeRunDirectory() {
        CheckpointStore store = new CheckpointStore(projectRoot, codec);

        assertThrows(IllegalArgumentException.class, () -> store.statePath("../outside"));
    }

    @Test
    void rejectsCheckpointWhoseEmbeddedRunIdDoesNotMatchItsDirectory() throws IOException {
        CheckpointStore store = new CheckpointStore(projectRoot, codec);
        store.save(checkpoint("run-a", 1));
        Path mismatched = store.statePath("run-b");
        Files.createDirectories(mismatched.getParent());
        Files.copy(store.statePath("run-a"), mismatched);

        assertThrows(CheckpointException.class, () -> store.load("run-b"));
    }

    @Test
    void rejectsRunDirectorySymbolicLinkThatEscapesTheProject() throws IOException {
        Path configuredRoot = projectRoot.resolve("project");
        Path outside = projectRoot.resolve("outside");
        Files.createDirectories(configuredRoot.resolve(".oml/runs"));
        Files.createDirectories(outside);
        Files.createSymbolicLink(configuredRoot.resolve(".oml/runs/escape"), outside);
        CheckpointStore store = new CheckpointStore(configuredRoot, codec);

        assertThrows(CheckpointException.class, () -> store.save(checkpoint("escape", 1)));
        assertFalse(Files.exists(outside.resolve("state.json")));
    }

    private static RunCheckpoint checkpoint(String runId, int executedSteps) {
        NodeId current = new NodeId("node-" + executedSteps);
        RunState state = new RunState(
                RunStatus.RUNNING,
                current,
                executedSteps,
                Map.of("step", Integer.toString(executedSteps)),
                List.of(current),
                List.of());
        return RunCheckpoint.current(
                runId,
                "graph-signature",
                CheckpointPhase.READY,
                state);
    }
}
