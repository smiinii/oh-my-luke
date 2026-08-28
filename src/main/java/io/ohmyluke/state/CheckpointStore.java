package io.ohmyluke.state;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;

/** Persists checkpoints with an atomic primary file and one last-known-good backup. */
public final class CheckpointStore {
    private static final String STATE_FILE = "state.json";
    private static final String BACKUP_FILE = "state.json.bak";

    private final Path projectRoot;
    private final CheckpointCodec codec;

    public CheckpointStore(Path projectRoot, CheckpointCodec codec) {
        this.projectRoot = RunFileSupport.normalizeRoot(projectRoot);
        this.codec = Objects.requireNonNull(codec, "codec");
    }

    public void save(RunCheckpoint checkpoint) {
        Objects.requireNonNull(checkpoint, "checkpoint");
        Path state = statePath(checkpoint.runId());
        Path runDirectory = state.getParent();
        Path temporary = null;
        Path backup = backupPath(checkpoint.runId());
        Path backupTemporary = null;
        try {
            Files.createDirectories(runDirectory);
            temporary = Files.createTempFile(runDirectory, STATE_FILE + ".", ".tmp");
            RunFileSupport.writeDurably(temporary, codec.encode(checkpoint));
            if (Files.exists(state) && isReadyCheckpoint(state)) {
                backupTemporary = Files.createTempFile(runDirectory, BACKUP_FILE + ".", ".tmp");
                Files.copy(state, backupTemporary, StandardCopyOption.REPLACE_EXISTING);
                RunFileSupport.forceFile(backupTemporary);
                RunFileSupport.moveAtomically(backupTemporary, backup);
            }
            RunFileSupport.moveAtomically(temporary, state);
        } catch (IOException error) {
            throw new CheckpointException("failed to save checkpoint for " + checkpoint.runId(), error);
        } finally {
            RunFileSupport.deleteTemporary(temporary);
            RunFileSupport.deleteTemporary(backupTemporary);
        }
    }

    public CheckpointLoadResult load(String runId) {
        Path state = statePath(runId);
        try {
            return new CheckpointLoadResult(readForRun(state, runId), false);
        } catch (UnsupportedCheckpointVersionException error) {
            throw error;
        } catch (CheckpointException primaryError) {
            Path backup = backupPath(runId);
            try {
                return new CheckpointLoadResult(readForRun(backup, runId), true);
            } catch (CheckpointException backupError) {
                primaryError.addSuppressed(backupError);
                throw primaryError;
            }
        }
    }

    public Path statePath(String runId) {
        return RunFileSupport.file(projectRoot, runId, STATE_FILE);
    }

    public boolean exists(String runId) {
        return Files.exists(statePath(runId));
    }

    private Path backupPath(String runId) {
        return RunFileSupport.file(projectRoot, runId, BACKUP_FILE);
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

    private RunCheckpoint readForRun(Path path, String expectedRunId) {
        RunCheckpoint checkpoint = read(path);
        if (!checkpoint.runId().equals(expectedRunId)) {
            throw new CheckpointException(
                    "checkpoint runId does not match directory: expected "
                            + expectedRunId
                            + " but was "
                            + checkpoint.runId());
        }
        return checkpoint;
    }

    private boolean isReadyCheckpoint(Path path) {
        try {
            return read(path).phase() == CheckpointPhase.READY;
        } catch (UnsupportedCheckpointVersionException error) {
            throw error;
        } catch (CheckpointException error) {
            return false;
        }
    }

}
