package io.ohmyluke.state;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.regex.Pattern;

/** Persists checkpoints with an atomic primary file and one last-known-good backup. */
public final class CheckpointStore {
    private static final Pattern SAFE_RUN_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");
    private static final String STATE_FILE = "state.json";
    private static final String BACKUP_FILE = "state.json.bak";

    private final Path projectRoot;
    private final CheckpointCodec codec;

    public CheckpointStore(Path projectRoot, CheckpointCodec codec) {
        this.projectRoot = Objects.requireNonNull(projectRoot, "projectRoot").toAbsolutePath().normalize();
        this.codec = Objects.requireNonNull(codec, "codec");
    }

    public void save(RunCheckpoint checkpoint) {
        Objects.requireNonNull(checkpoint, "checkpoint");
        Path state = statePath(checkpoint.runId());
        Path runDirectory = state.getParent();
        Path temporary = runDirectory.resolve(STATE_FILE + ".tmp");
        Path backup = runDirectory.resolve(BACKUP_FILE);
        Path backupTemporary = runDirectory.resolve(BACKUP_FILE + ".tmp");
        try {
            Files.createDirectories(runDirectory);
            writeDurably(temporary, codec.encode(checkpoint));
            if (Files.exists(state)) {
                Files.copy(state, backupTemporary, StandardCopyOption.REPLACE_EXISTING);
                forceFile(backupTemporary);
                moveAtomically(backupTemporary, backup);
            }
            moveAtomically(temporary, state);
        } catch (IOException error) {
            throw new CheckpointException("failed to save checkpoint for " + checkpoint.runId(), error);
        } finally {
            deleteTemporary(temporary);
            deleteTemporary(backupTemporary);
        }
    }

    public CheckpointLoadResult load(String runId) {
        Path state = statePath(runId);
        try {
            return new CheckpointLoadResult(read(state), false);
        } catch (UnsupportedCheckpointVersionException error) {
            throw error;
        } catch (CheckpointException primaryError) {
            Path backup = state.getParent().resolve(BACKUP_FILE);
            try {
                return new CheckpointLoadResult(read(backup), true);
            } catch (CheckpointException backupError) {
                primaryError.addSuppressed(backupError);
                throw primaryError;
            }
        }
    }

    public Path statePath(String runId) {
        validateRunId(runId);
        return projectRoot.resolve(".oml").resolve("runs").resolve(runId).resolve(STATE_FILE);
    }

    private RunCheckpoint read(Path path) {
        try {
            return codec.decode(Files.readString(path, StandardCharsets.UTF_8));
        } catch (UnsupportedCheckpointVersionException error) {
            throw error;
        } catch (IOException error) {
            throw new CheckpointException("failed to read checkpoint: " + path, error);
        }
    }

    private static void writeDurably(Path path, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        try (FileChannel channel = FileChannel.open(
                path,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
        }
    }

    private static void forceFile(Path path) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(
                    source,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException error) {
            throw new CheckpointException("atomic checkpoint replacement is not supported: " + target, error);
        }
    }

    private static void validateRunId(String runId) {
        Objects.requireNonNull(runId, "runId");
        if (!SAFE_RUN_ID.matcher(runId).matches()) {
            throw new IllegalArgumentException("invalid runId: " + runId);
        }
    }

    private static void deleteTemporary(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // A stale temporary file is never treated as a valid checkpoint.
        }
    }
}
