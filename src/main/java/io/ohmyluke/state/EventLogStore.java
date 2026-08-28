package io.ohmyluke.state;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
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
            Path path = eventsPath(event.runId());
            String separator = repairIncompleteTail(path);
            RunFileSupport.appendDurably(path, separator + codec.encode(event) + "\n");
        } catch (IOException error) {
            throw new CheckpointException("failed to append run event for " + event.runId(), error);
        }
    }

    public EventLogReadResult readAll(String runId) {
        Path path = eventsPath(runId);
        if (Files.notExists(path)) {
            return new EventLogReadResult(List.of(), false);
        }
        try {
            byte[] content = Files.readAllBytes(path);
            boolean terminatedByNewline = content.length > 0
                    && content[content.length - 1] == '\n';
            List<RunEvent> events = new ArrayList<>();
            boolean ignoredTail = false;
            int lineStart = 0;
            for (int index = 0; index <= content.length; index++) {
                if (index < content.length && content[index] != '\n') {
                    continue;
                }
                boolean incompleteFinalLine = index == content.length && !terminatedByNewline;
                try {
                    String line = decodeUtf8(content, lineStart, index);
                    if (!line.isBlank()) {
                        events.add(codec.decode(line));
                    }
                } catch (UnsupportedCheckpointVersionException error) {
                    throw error;
                } catch (CheckpointException error) {
                    if (!incompleteFinalLine) {
                        throw error;
                    }
                    ignoredTail = true;
                }
                lineStart = index + 1;
            }
            return new EventLogReadResult(events, ignoredTail);
        } catch (IOException error) {
            throw new CheckpointException("failed to read run events: " + path, error);
        }
    }

    public Path eventsPath(String runId) {
        return RunFileSupport.file(projectRoot, runId, EVENTS_FILE);
    }

    private String repairIncompleteTail(Path path) throws IOException {
        if (Files.notExists(path)) {
            return "";
        }
        byte[] content = Files.readAllBytes(path);
        if (content.length == 0 || content[content.length - 1] == '\n') {
            return "";
        }
        int lastNewline = lastIndexOfNewline(content);
        try {
            codec.decode(decodeUtf8(content, lastNewline + 1, content.length));
            return "\n";
        } catch (UnsupportedCheckpointVersionException error) {
            throw error;
        } catch (CheckpointException error) {
            RunFileSupport.writeDurably(path, Arrays.copyOf(content, lastNewline + 1));
            return "";
        }
    }

    private static String decodeUtf8(byte[] bytes, int start, int end) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .decode(ByteBuffer.wrap(bytes, start, end - start))
                    .toString();
        } catch (CharacterCodingException error) {
            throw new CheckpointException("run event contains malformed UTF-8", error);
        }
    }

    private static int lastIndexOfNewline(byte[] content) {
        for (int index = content.length - 1; index >= 0; index--) {
            if (content[index] == '\n') {
                return index;
            }
        }
        return -1;
    }
}
