package io.ohmyluke.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ohmyluke.graph.NodeId;
import io.ohmyluke.graph.RunStatus;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EventLogStoreTest {
    @TempDir
    Path projectRoot;

    @Test
    void missingLogIsAnEmptyHistory() {
        EventLogStore store = new EventLogStore(projectRoot, new RunEventCodec());

        EventLogReadResult result = store.readAll("run-001");

        assertEquals(List.of(), result.events());
        assertFalse(result.ignoredIncompleteTail());
    }

    @Test
    void appendsAndReadsEventsInSequence() throws IOException {
        EventLogStore store = new EventLogStore(projectRoot, new RunEventCodec());
        RunEvent started = event(1, RunEventType.NODE_STARTED, "write", 0);
        RunEvent completed = event(2, RunEventType.NODE_COMPLETED, "write", 1);

        store.append(started);
        store.append(completed);
        EventLogReadResult result = store.readAll("run-001");

        assertEquals(List.of(started, completed), result.events());
        assertFalse(result.ignoredIncompleteTail());
        assertEquals(2, Files.readAllLines(store.eventsPath("run-001")).size());
    }

    @Test
    void ignoresOnlyAnIncompleteFinalLine() throws IOException {
        EventLogStore store = new EventLogStore(projectRoot, new RunEventCodec());
        RunEvent started = event(1, RunEventType.NODE_STARTED, "write", 0);
        store.append(started);
        Files.writeString(
                store.eventsPath("run-001"),
                "{\"schemaVersion\":1",
                StandardOpenOption.APPEND);

        EventLogReadResult result = store.readAll("run-001");

        assertEquals(List.of(started), result.events());
        assertTrue(result.ignoredIncompleteTail());
    }

    @Test
    void appendingAfterAnIncompleteTailRepairsTheLog() throws IOException {
        EventLogStore store = new EventLogStore(projectRoot, new RunEventCodec());
        RunEvent started = event(1, RunEventType.NODE_STARTED, "write", 0);
        RunEvent completed = event(2, RunEventType.NODE_COMPLETED, "write", 1);
        store.append(started);
        Files.writeString(
                store.eventsPath("run-001"),
                "{\"schemaVersion\":1",
                StandardOpenOption.APPEND);

        store.append(completed);
        EventLogReadResult result = store.readAll("run-001");

        assertEquals(List.of(started, completed), result.events());
        assertFalse(result.ignoredIncompleteTail());
    }

    @Test
    void repairsATailTruncatedInsideAMultibyteUtf8Character() throws IOException {
        EventLogStore store = new EventLogStore(projectRoot, new RunEventCodec());
        RunEvent started = event(1, RunEventType.NODE_STARTED, "write", 0);
        RunEvent completed = event(2, RunEventType.NODE_COMPLETED, "write", 1);
        store.append(started);
        Files.write(
                store.eventsPath("run-001"),
                new byte[] {(byte) 0xED, (byte) 0x95},
                StandardOpenOption.APPEND);

        EventLogReadResult interrupted = store.readAll("run-001");
        assertEquals(List.of(started), interrupted.events());
        assertTrue(interrupted.ignoredIncompleteTail());

        store.append(completed);
        assertEquals(List.of(started, completed), store.readAll("run-001").events());
    }

    @Test
    void rejectsCorruptionBeforeTheFinalLine() throws IOException {
        EventLogStore store = new EventLogStore(projectRoot, new RunEventCodec());
        RunEvent completed = event(2, RunEventType.NODE_COMPLETED, "write", 1);
        Path path = store.eventsPath("run-001");
        Files.createDirectories(path.getParent());
        Files.writeString(path, "{broken}\n" + new RunEventCodec().encode(completed) + "\n");

        assertThrows(CheckpointException.class, () -> store.readAll("run-001"));
    }

    private static RunEvent event(
            long sequence,
            RunEventType type,
            String node,
            int executedSteps) {
        return RunEvent.current(
                "run-001",
                sequence,
                type,
                new NodeId(node),
                RunStatus.RUNNING,
                executedSteps,
                type.name());
    }
}
