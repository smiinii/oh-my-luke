package io.ohmyluke.state;

import java.util.Objects;

/** A loaded checkpoint and whether the primary file required backup recovery. */
public record CheckpointLoadResult(RunCheckpoint checkpoint, boolean recoveredFromBackup) {
    public CheckpointLoadResult {
        Objects.requireNonNull(checkpoint, "checkpoint");
    }
}
