package io.ohmyluke.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Stores complete pre-mutation snapshots under OML-owned state and restores them on demand. */
public final class FileCheckpointStore {
    private static final int SCHEMA_VERSION = 1;
    private static final int MAX_FILES = 1_000;
    private static final long MAX_BYTES = 32L * 1024 * 1024;
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

    private final Path projectRoot;
    private final String runId;
    private final Path checkpointRoot;
    private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    public FileCheckpointStore(Path projectRoot, String runId) {
        try {
            this.projectRoot = Objects.requireNonNull(projectRoot, "projectRoot").toRealPath();
        } catch (IOException error) {
            throw new FileCheckpointException("failed to resolve project root", error);
        }
        this.runId = validateId(runId, "runId");
        this.checkpointRoot = this.projectRoot
                .resolve(".oml/runs")
                .resolve(this.runId)
                .resolve("file-checkpoints");
    }

    public String capture(String operationId, List<Path> roots) {
        String checkpointId = validateId(operationId, "operationId");
        List<Path> normalizedRoots = List.copyOf(new java.util.LinkedHashSet<>(Objects.requireNonNull(roots, "roots")))
                .stream()
                .map(path -> Objects.requireNonNull(path, "checkpoint path").toAbsolutePath().normalize())
                .toList();
        if (normalizedRoots.isEmpty()) {
            throw new IllegalArgumentException("roots must not be empty");
        }
        SnapshotCollector collector = new SnapshotCollector();
        for (Path root : normalizedRoots) {
            collector.capture(root);
        }
        FileCheckpoint checkpoint = new FileCheckpoint(
                SCHEMA_VERSION,
                checkpointId,
                normalizedRoots.stream().map(Path::toString).toList(),
                collector.snapshots());
        writeAtomically(checkpointPath(checkpointId), encode(checkpoint));
        return checkpointId;
    }

    public void restore(String checkpointId) {
        String validated = validateId(checkpointId, "checkpointId");
        FileCheckpoint checkpoint = decode(read(checkpointPath(validated)));
        if (checkpoint.schemaVersion() != SCHEMA_VERSION || !checkpoint.checkpointId().equals(validated)) {
            throw new FileCheckpointException("invalid file checkpoint identity or schema");
        }

        List<Path> roots = checkpoint.roots().stream().map(Path::of).toList();
        for (Path root : roots) {
            rejectSymlinkComponents(root);
            deleteTree(root);
        }
        checkpoint.snapshots().stream()
                .filter(snapshot -> snapshot.type() == SnapshotType.DIRECTORY)
                .sorted(Comparator.comparingInt(snapshot -> Path.of(snapshot.path()).getNameCount()))
                .forEach(snapshot -> createDirectory(Path.of(snapshot.path())));
        checkpoint.snapshots().stream()
                .filter(snapshot -> snapshot.type() == SnapshotType.FILE)
                .forEach(snapshot -> writeFile(Path.of(snapshot.path()), snapshot.content()));
    }

    public Path checkpointPath(String checkpointId) {
        return checkpointRoot.resolve(validateId(checkpointId, "checkpointId") + ".json");
    }

    private String encode(FileCheckpoint checkpoint) {
        try {
            return mapper.writeValueAsString(checkpoint);
        } catch (JsonProcessingException error) {
            throw new FileCheckpointException("failed to encode file checkpoint", error);
        }
    }

    private FileCheckpoint decode(String json) {
        try {
            return mapper.readValue(json, FileCheckpoint.class);
        } catch (JsonProcessingException error) {
            throw new FileCheckpointException("failed to decode file checkpoint", error);
        }
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException error) {
            throw new FileCheckpointException("failed to read file checkpoint: " + path, error);
        }
    }

    private void writeAtomically(Path target, String content) {
        Path temporary = null;
        try {
            createCheckpointDirectory();
            temporary = Files.createTempFile(checkpointRoot, target.getFileName() + ".", ".tmp");
            byte[] bytes = content.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            try (FileChannel channel = FileChannel.open(
                    temporary,
                    StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException error) {
                throw new FileCheckpointException("atomic checkpoint replacement is unavailable", error);
            }
        } catch (IOException error) {
            throw new FileCheckpointException("failed to write file checkpoint", error);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // A temporary file is never treated as a valid checkpoint.
                }
            }
        }
    }

    private void createCheckpointDirectory() throws IOException {
        Path oml = projectRoot.resolve(".oml");
        if (Files.isSymbolicLink(oml)) {
            throw new FileCheckpointException(".oml must not be a symbolic link");
        }
        Files.createDirectories(checkpointRoot);
        if (!checkpointRoot.toRealPath().startsWith(projectRoot)) {
            throw new FileCheckpointException("checkpoint directory escapes the project");
        }
    }

    static void deleteTree(Path root) {
        if (Files.notExists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        rejectSymlinkComponents(root);
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path directory, IOException error) throws IOException {
                    if (error != null) {
                        throw error;
                    }
                    Files.delete(directory);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException error) {
            throw new FileCheckpointException("failed to delete path during file operation: " + root, error);
        }
    }

    static void writeFile(Path target, byte[] content) {
        Path temporary = null;
        try {
            Path parent = target.getParent();
            if (parent == null || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
                throw new FileCheckpointException("target parent must be an existing directory: " + target);
            }
            rejectSymlinkComponents(parent);
            temporary = Files.createTempFile(parent, target.getFileName() + ".", ".tmp");
            Files.write(temporary, content, StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException error) {
                throw new FileCheckpointException("atomic file replacement is unavailable: " + target, error);
            }
        } catch (IOException error) {
            throw new FileCheckpointException("failed to write file: " + target, error);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // A temporary file is not user data.
                }
            }
        }
    }

    private static void createDirectory(Path path) {
        try {
            Path parent = path.getParent();
            if (parent == null || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
                throw new FileCheckpointException("directory parent must exist: " + path);
            }
            rejectSymlinkComponents(parent);
            Files.createDirectory(path);
        } catch (IOException error) {
            throw new FileCheckpointException("failed to restore directory: " + path, error);
        }
    }

    private static void rejectSymlinkComponents(Path absolute) {
        Path inspected = absolute.getRoot();
        for (Path part : absolute.toAbsolutePath().normalize()) {
            inspected = inspected == null ? part : inspected.resolve(part);
            if (Files.isSymbolicLink(inspected)) {
                throw new FileCheckpointException("symbolic links are not accepted: " + absolute);
            }
            if (Files.notExists(inspected, LinkOption.NOFOLLOW_LINKS)) {
                break;
            }
        }
    }

    private static String validateId(String value, String name) {
        Objects.requireNonNull(value, name);
        if (!SAFE_ID.matcher(value).matches()) {
            throw new IllegalArgumentException("invalid " + name + ": " + value);
        }
        return value;
    }

    public record FileCheckpoint(
            int schemaVersion,
            String checkpointId,
            List<String> roots,
            List<FileSnapshot> snapshots) {
        public FileCheckpoint {
            checkpointId = validateId(checkpointId, "checkpointId");
            roots = List.copyOf(Objects.requireNonNull(roots, "roots"));
            snapshots = List.copyOf(Objects.requireNonNull(snapshots, "snapshots"));
        }
    }

    public record FileSnapshot(String path, SnapshotType type, byte[] content) {
        public FileSnapshot {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(type, "type");
            content = content == null ? new byte[0] : java.util.Arrays.copyOf(content, content.length);
            if (type != SnapshotType.FILE && content.length > 0) {
                throw new IllegalArgumentException(type + " snapshot must not contain bytes");
            }
        }

        @Override
        public byte[] content() {
            return java.util.Arrays.copyOf(content, content.length);
        }
    }

    public enum SnapshotType {
        MISSING,
        DIRECTORY,
        FILE
    }

    private static final class SnapshotCollector {
        private final List<FileSnapshot> snapshots = new ArrayList<>();
        private final Set<Path> seen = new HashSet<>();
        private long bytes;

        void capture(Path root) {
            Path normalized = root.toAbsolutePath().normalize();
            if (!seen.add(normalized)) {
                return;
            }
            rejectSymlinkComponents(normalized);
            if (Files.notExists(normalized, LinkOption.NOFOLLOW_LINKS)) {
                add(new FileSnapshot(normalized.toString(), SnapshotType.MISSING, null));
                return;
            }
            try {
                Files.walkFileTree(normalized, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                        add(new FileSnapshot(
                                directory.toAbsolutePath().normalize().toString(),
                                SnapshotType.DIRECTORY,
                                null));
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                        if (attributes.isSymbolicLink() || !attributes.isRegularFile()) {
                            throw new FileCheckpointException("unsupported file type in checkpoint: " + file);
                        }
                        byte[] content = Files.readAllBytes(file);
                        bytes += content.length;
                        if (bytes > MAX_BYTES) {
                            throw new FileCheckpointException("file checkpoint exceeds " + MAX_BYTES + " bytes");
                        }
                        add(new FileSnapshot(
                                file.toAbsolutePath().normalize().toString(),
                                SnapshotType.FILE,
                                content));
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException error) {
                throw new FileCheckpointException("failed to capture path: " + normalized, error);
            }
        }

        private void add(FileSnapshot snapshot) {
            if (snapshots.size() >= MAX_FILES) {
                throw new FileCheckpointException("file checkpoint exceeds " + MAX_FILES + " entries");
            }
            snapshots.add(snapshot);
        }

        List<FileSnapshot> snapshots() {
            return List.copyOf(snapshots);
        }
    }
}
