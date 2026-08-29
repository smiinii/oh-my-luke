package io.ohmyluke.ai.codex;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

final class CodexInvocationStore {
    private static final long METADATA_ALLOWANCE = 64L * 1024;
    private static final ReentrantLock[] JVM_LOCKS = createJvmLocks();
    private final Path projectRoot;
    private final Path directory;
    private final long maxStoredBytes;
    private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    CodexInvocationStore(Path projectRoot, int maxOutputBytes) {
        this.projectRoot = Objects.requireNonNull(projectRoot, "projectRoot");
        this.directory = projectRoot.resolve(".oml/runtime/codex/invocations");
        this.maxStoredBytes = Math.addExact(Math.multiplyExact((long) maxOutputBytes, 8), METADATA_ALLOWANCE);
    }

    LockedInvocation lock(String invocationId) {
        String fileId = CodexHashing.safeFileId(invocationId);
        ensureSafeDirectory();
        Path lockPath = directory.resolve(fileId + ".lock");
        ReentrantLock jvmLock = JVM_LOCKS[Math.floorMod(lockPath.hashCode(), JVM_LOCKS.length)];
        jvmLock.lock();
        FileChannel channel = null;
        try {
            channel = FileChannel.open(
                    lockPath,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS);
            FileLock lock = channel.lock();
            return new LockedInvocation(
                    jvmLock,
                    channel,
                    lock,
                    directory.resolve(fileId + ".json"));
        } catch (IOException error) {
            closeQuietly(channel);
            jvmLock.unlock();
            throw new CodexStoreException("failed to lock Codex invocation state", error);
        } catch (RuntimeException error) {
            closeQuietly(channel);
            jvmLock.unlock();
            throw error;
        }
    }

    final class LockedInvocation implements AutoCloseable {
        private final ReentrantLock jvmLock;
        private final FileChannel channel;
        private final FileLock lock;
        private final Path resultPath;

        private LockedInvocation(
                ReentrantLock jvmLock,
                FileChannel channel,
                FileLock lock,
                Path resultPath) {
            this.jvmLock = jvmLock;
            this.channel = channel;
            this.lock = lock;
            this.resultPath = resultPath;
        }

        Optional<CodexStoredInvocation> load() {
            if (Files.notExists(resultPath, LinkOption.NOFOLLOW_LINKS)) {
                return Optional.empty();
            }
            if (Files.isSymbolicLink(resultPath)) {
                throw new CodexStoreException("Codex invocation result must not be a symbolic link");
            }
            try {
                long size = Files.size(resultPath);
                if (size > maxStoredBytes) {
                    throw new CodexStoreException("Codex invocation result exceeds its storage limit");
                }
                CodexStoredInvocation stored = mapper.readValue(
                        Files.readAllBytes(resultPath),
                        CodexStoredInvocation.class);
                return Optional.of(stored);
            } catch (JsonProcessingException error) {
                throw new CodexStoreException("Codex invocation result is invalid", error);
            } catch (IOException error) {
                throw new CodexStoreException("failed to read Codex invocation result", error);
            }
        }

        void save(CodexStoredInvocation stored) {
            byte[] encoded;
            try {
                encoded = mapper.writeValueAsBytes(stored);
            } catch (JsonProcessingException error) {
                throw new CodexStoreException("failed to encode Codex invocation result", error);
            }
            if (encoded.length > maxStoredBytes) {
                throw new CodexStoreException("Codex invocation result exceeds its storage limit");
            }
            Path temporary = null;
            try {
                temporary = Files.createTempFile(directory, resultPath.getFileName() + ".", ".tmp");
                try (FileChannel output = FileChannel.open(
                        temporary,
                        StandardOpenOption.WRITE,
                        StandardOpenOption.TRUNCATE_EXISTING,
                        LinkOption.NOFOLLOW_LINKS)) {
                    ByteBuffer buffer = ByteBuffer.wrap(encoded);
                    while (buffer.hasRemaining()) {
                        output.write(buffer);
                    }
                    output.force(true);
                }
                try {
                    Files.move(
                            temporary,
                            resultPath,
                            StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException error) {
                    throw new CodexStoreException("atomic Codex invocation storage is unavailable", error);
                }
            } catch (IOException error) {
                throw new CodexStoreException("failed to save Codex invocation result", error);
            } finally {
                if (temporary != null) {
                    try {
                        Files.deleteIfExists(temporary);
                    } catch (IOException ignored) {
                        // Temporary data is never considered a valid invocation result.
                    }
                }
            }
        }

        @Override
        public void close() {
            try {
                lock.release();
            } catch (IOException ignored) {
                // Closing the channel below also releases the operating-system lock.
            }
            try {
                channel.close();
            } catch (IOException ignored) {
                // The completed operation result is unaffected by close failure.
            } finally {
                jvmLock.unlock();
            }
        }
    }

    private static ReentrantLock[] createJvmLocks() {
        ReentrantLock[] locks = new ReentrantLock[64];
        for (int index = 0; index < locks.length; index++) {
            locks[index] = new ReentrantLock();
        }
        return locks;
    }

    private static void closeQuietly(FileChannel channel) {
        if (channel == null) {
            return;
        }
        try {
            channel.close();
        } catch (IOException ignored) {
            // The lock acquisition already failed.
        }
    }

    private void ensureSafeDirectory() {
        Path existing = directory;
        while (existing != null && Files.notExists(existing, LinkOption.NOFOLLOW_LINKS)) {
            existing = existing.getParent();
        }
        if (existing == null) {
            throw new CodexStoreException("no existing ancestor for Codex invocation storage");
        }
        try {
            if (!existing.toRealPath().startsWith(projectRoot)) {
                throw new CodexStoreException("Codex invocation storage escapes the project");
            }
            Files.createDirectories(directory);
            Path current = projectRoot;
            for (Path part : projectRoot.relativize(directory)) {
                current = current.resolve(part);
                if (Files.isSymbolicLink(current)) {
                    throw new CodexStoreException("Codex invocation storage contains a symbolic link");
                }
            }
            if (!directory.toRealPath().startsWith(projectRoot)) {
                throw new CodexStoreException("Codex invocation storage escapes the project");
            }
        } catch (IOException error) {
            throw new CodexStoreException("failed to prepare Codex invocation storage", error);
        }
    }

    static final class CodexStoreException extends RuntimeException {
        CodexStoreException(String message) {
            super(message);
        }

        CodexStoreException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
