package io.ohmyluke.state;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Provides one non-blocking operating-system file lock per run. */
public final class RunLockManager {
    private static final String LOCK_FILE = "run.lock";

    private final Path projectRoot;

    public RunLockManager(Path projectRoot) {
        this.projectRoot = RunFileSupport.normalizeRoot(projectRoot);
    }

    public RunLease acquire(String runId) {
        Path path = RunFileSupport.file(projectRoot, runId, LOCK_FILE);
        FileChannel channel = null;
        try {
            Files.createDirectories(path.getParent());
            channel = FileChannel.open(
                    path,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS);
            FileLock lock = channel.tryLock();
            if (lock == null) {
                channel.close();
                throw new CheckpointException("run is already active: " + runId);
            }
            return new RunLease(channel, lock);
        } catch (OverlappingFileLockException error) {
            closeQuietly(channel);
            throw new CheckpointException("run is already active: " + runId, error);
        } catch (IOException error) {
            closeQuietly(channel);
            throw new CheckpointException("failed to acquire run lock: " + runId, error);
        }
    }

    private static void closeQuietly(FileChannel channel) {
        if (channel == null) {
            return;
        }
        try {
            channel.close();
        } catch (IOException ignored) {
            // The original lock acquisition failure remains the useful error.
        }
    }

    public static final class RunLease implements AutoCloseable {
        private final FileChannel channel;
        private final FileLock lock;

        private RunLease(FileChannel channel, FileLock lock) {
            this.channel = channel;
            this.lock = lock;
        }

        @Override
        public void close() {
            try {
                lock.release();
            } catch (IOException error) {
                throw new CheckpointException("failed to release run lock", error);
            } finally {
                closeQuietly(channel);
            }
        }
    }
}
