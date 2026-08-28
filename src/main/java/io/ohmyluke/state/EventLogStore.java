package io.ohmyluke.state;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Appends durable run events and tolerates only a crash-truncated final line. */
public final class EventLogStore {
    private static final String EVENTS_FILE = "events.jsonl";

    private final Path projectRoot;
    private final RunEventCodec codec;

    public EventLogStore(Path projectRoot, RunEventCodec codec) {
        this.projectRoot = RunFileSupport.normalizeRoot(projectRoot);
        this.codec = Objects.requireNonNull(codec, "codec");
    }

    public void append(RunEvent event) {
        Objects.requireNonNull(event, "event");
        try {
            RunFileSupport.appendDurably(eventsPath(event.runId()), codec.encode(event) + "\n");
        } catch (IOException error) {
            throw new CheckpointException("failed to append run event for " + event.runId(), error);
        }
    }

    public EventLogReadResult readAll(String runId) {
        Path path = eventsPath(runId);
        try {
            String content = Files.readString(path, StandardCharsets.UTF_8);
            boolean terminatedByNewline = content.endsWith("\n");
            String[] lines = content.split("\n", -1);
            List<RunEvent> events = new ArrayList<>();
            boolean ignoredTail = false;
            for (int index = 0; index < lines.length; index++) {
                String line = lines[index];
                if (line.isBlank()) {
                    continue;
                }
                try {
                    events.add(codec.decode(line));
                } catch (UnsupportedCheckpointVersionException error) {
                    throw error;
                } catch (CheckpointException error) {
                    boolean incompleteFinalLine = index == lines.length - 1 && !terminatedByNewline;
                    if (!incompleteFinalLine) {
                        throw error;
                    }
                    ignoredTail = true;
                }
            }
            return new EventLogReadResult(events, ignoredTail);
        } catch (IOException error) {
            throw new CheckpointException("failed to read run events: " + path, error);
        }
    }

    public Path eventsPath(String runId) {
        return RunFileSupport.file(projectRoot, runId, EVENTS_FILE);
    }
}
