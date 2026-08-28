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

/** Shared safe path and durable file operations for one run directory. */
final class RunFileSupport {
    private static final Pattern SAFE_RUN_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

    private RunFileSupport() {}

    static Path normalizeRoot(Path projectRoot) {
        return Objects.requireNonNull(projectRoot, "projectRoot").toAbsolutePath().normalize();
    }

    static Path file(Path projectRoot, String runId, String fileName) {
        validateRunId(runId);
        return projectRoot.resolve(".oml").resolve("runs").resolve(runId).resolve(fileName);
    }

    static void writeAtomically(Path target, String content) {
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            Files.createDirectories(target.getParent());
            writeDurably(temporary, content);
            moveAtomically(temporary, target);
        } catch (IOException error) {
            throw new CheckpointException("failed to write file atomically: " + target, error);
        } finally {
            deleteTemporary(temporary);
        }
    }

    static void writeDurably(Path path, String content) throws IOException {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        try (FileChannel channel = FileChannel.open(
                path,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE)) {
            writeFully(channel, bytes);
            channel.force(true);
        }
    }

    static void appendDurably(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        try (FileChannel channel = FileChannel.open(
                path,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.APPEND)) {
            writeFully(channel, bytes);
            channel.force(true);
        }
    }

    static void forceFile(Path path) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }

    static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(
                    source,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException error) {
            throw new CheckpointException("atomic replacement is not supported: " + target, error);
        }
    }

    static void deleteTemporary(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Temporary files are never considered valid run state.
        }
    }

    private static void writeFully(FileChannel channel, byte[] bytes) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        while (buffer.hasRemaining()) {
            channel.write(buffer);
        }
    }

    private static void validateRunId(String runId) {
        Objects.requireNonNull(runId, "runId");
        if (!SAFE_RUN_ID.matcher(runId).matches()) {
            throw new IllegalArgumentException("invalid runId: " + runId);
        }
    }
}
