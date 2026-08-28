package io.ohmyluke.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.ohmyluke.policy.ToolCapability;
import io.ohmyluke.policy.ToolPermissionRequest;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.DosFileAttributeView;
import java.nio.file.attribute.DosFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Trusted, immutable pre-mutation snapshots used only by the structured file tool. */
final class FileCheckpointStore {
    private static final int SCHEMA_VERSION = 2;
    private static final int MAX_FILES = 1_000;
    private static final long MAX_BYTES = 32L * 1024 * 1024;
    private static final long MAX_MANIFEST_BYTES = 48L * 1024 * 1024;
    private static final int INTEGRITY_KEY_BYTES = 32;
    private static final String INTEGRITY_KEY_FILE = "file-checkpoint-integrity.key";
    private static final ConcurrentHashMap<Path, Object> JVM_LOCKS = new ConcurrentHashMap<>();
    private static final ThreadLocal<Set<Path>> HELD_LOCKS = ThreadLocal.withInitial(HashSet::new);
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

    private final Path projectRoot;
    private final String runId;
    private final Path checkpointRoot;
    private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    FileCheckpointStore(Path projectRoot, String runId) {
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

    String capture(FileToolRequest operation, ToolPermissionRequest permission, List<Path> roots) {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(permission, "permission");
        String checkpointId = validateId(operation.operationId(), "operationId");
        return withCheckpointLock(checkpointId, () -> captureLocked(operation, permission, roots));
    }

    private String captureLocked(
            FileToolRequest operation,
            ToolPermissionRequest permission,
            List<Path> roots) {
        String checkpointId = operation.operationId();
        List<Path> normalizedRoots = normalizeRoots(roots);
        validateRootShape(operation.operation(), normalizedRoots);
        validateBinding(operation, permission, normalizedRoots);

        Path target = checkpointPath(checkpointId);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            validateSameRequest(loadValidated(checkpointId), operation, permission, normalizedRoots);
            return checkpointId;
        }

        SnapshotCollector collector = new SnapshotCollector();
        normalizedRoots.forEach(collector::capture);
        FileCheckpoint unsigned = new FileCheckpoint(
                SCHEMA_VERSION,
                checkpointId,
                projectRoot.toString(),
                runId,
                operation.operation(),
                permission.capability(),
                permission.target(),
                requestFingerprint(operation, normalizedRoots),
                normalizedRoots.stream().map(Path::toString).toList(),
                collector.snapshots(),
                "");
        FileCheckpoint checkpoint = withIntegrity(unsigned, sign(unsigned, true));
        validateCheckpoint(checkpoint);
        try {
            writeAtomicallyNew(target, encode(checkpoint));
        } catch (CheckpointAlreadyExists ignored) {
            validateSameRequest(loadValidated(checkpointId), operation, permission, normalizedRoots);
        }
        return checkpointId;
    }

    void restore(String checkpointId) {
        String validated = validateId(checkpointId, "checkpointId");
        withMutationLock(() -> withCheckpointLock(validated, () -> {
                    restoreLocked(validated);
                    return null;
                }));
    }

    private void restoreLocked(String checkpointId) {
        FileCheckpoint checkpoint = loadValidated(checkpointId);
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
        checkpoint.snapshots().stream()
                .filter(snapshot -> snapshot.type() == SnapshotType.FILE)
                .forEach(snapshot -> applyMetadata(Path.of(snapshot.path()), snapshot));
        checkpoint.snapshots().stream()
                .filter(snapshot -> snapshot.type() == SnapshotType.DIRECTORY)
                .sorted(Comparator.comparingInt(
                                (FileSnapshot snapshot) -> Path.of(snapshot.path()).getNameCount())
                        .reversed())
                .forEach(snapshot -> applyMetadata(Path.of(snapshot.path()), snapshot));
    }

    boolean alreadyApplied(
            FileToolRequest operation,
            ToolPermissionRequest permission,
            List<Path> roots) {
        String checkpointId = validateId(operation.operationId(), "operationId");
        return withCheckpointLock(checkpointId, () -> {
            Path path = checkpointPath(checkpointId);
            if (Files.notExists(path, LinkOption.NOFOLLOW_LINKS)) {
                return false;
            }
            List<Path> normalizedRoots = normalizeRoots(roots);
            FileCheckpoint checkpoint = loadValidated(checkpointId);
            validateSameRequest(checkpoint, operation, permission, normalizedRoots);
            return switch (operation.operation()) {
                case WRITE -> fileHasContent(normalizedRoots.getFirst(), operation.content());
                case CREATE_DIRECTORY -> rootSnapshot(checkpoint, normalizedRoots.getFirst()).type()
                                == SnapshotType.MISSING
                        && Files.isDirectory(normalizedRoots.getFirst(), LinkOption.NOFOLLOW_LINKS);
                case DELETE -> Files.notExists(normalizedRoots.getFirst(), LinkOption.NOFOLLOW_LINKS);
                case MOVE -> moveWasApplied(checkpoint, normalizedRoots.get(0), normalizedRoots.get(1));
                case READ -> false;
            };
        });
    }

    private boolean moveWasApplied(FileCheckpoint checkpoint, Path source, Path destination) {
        if (Files.exists(source, LinkOption.NOFOLLOW_LINKS)
                || rootSnapshot(checkpoint, destination).type() != SnapshotType.MISSING
                || Files.notExists(destination, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        List<FileSnapshot> sourceSnapshots = checkpoint.snapshots().stream()
                .filter(snapshot -> Path.of(snapshot.path()).startsWith(source))
                .toList();
        for (FileSnapshot snapshot : sourceSnapshots) {
            Path expected = destination.resolve(source.relativize(Path.of(snapshot.path()))).normalize();
            boolean matches = switch (snapshot.type()) {
                case DIRECTORY -> Files.isDirectory(expected, LinkOption.NOFOLLOW_LINKS);
                case FILE -> fileHasContent(expected, snapshot.content());
                case MISSING -> false;
            };
            if (!matches) {
                return false;
            }
        }
        try (java.util.stream.Stream<Path> current = Files.walk(destination)) {
            return current.count() == sourceSnapshots.size();
        } catch (IOException error) {
            return false;
        }
    }

    private static boolean fileHasContent(Path path, byte[] expected) {
        try {
            return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                    && Files.size(path) == expected.length
                    && java.util.Arrays.equals(Files.readAllBytes(path), expected);
        } catch (IOException error) {
            return false;
        }
    }

    Path checkpointPath(String checkpointId) {
        return checkpointRoot.resolve(validateId(checkpointId, "checkpointId") + ".json");
    }

    <T> T withMutationLock(Supplier<T> action) {
        Objects.requireNonNull(action, "action");
        return withFileLock(projectRoot.resolve(".oml/file-tool-mutations.lock"), action);
    }

    private FileCheckpoint loadValidated(String checkpointId) {
        Path path = checkpointPath(checkpointId);
        if (Files.isSymbolicLink(path)) {
            throw new FileCheckpointException("file checkpoint must not be a symbolic link");
        }
        FileCheckpoint checkpoint = decode(readBounded(path));
        if (!checkpoint.checkpointId().equals(checkpointId)) {
            throw new FileCheckpointException("invalid file checkpoint identity");
        }
        validateCheckpoint(checkpoint);
        return checkpoint;
    }

    private void validateCheckpoint(FileCheckpoint checkpoint) {
        if (checkpoint.schemaVersion() != SCHEMA_VERSION
                || !checkpoint.projectRoot().equals(projectRoot.toString())
                || !checkpoint.runId().equals(runId)) {
            throw new FileCheckpointException("checkpoint schema, project, or run does not match");
        }
        String expectedIntegrity = sign(withIntegrity(checkpoint, ""), false);
        if (!MessageDigest.isEqual(
                expectedIntegrity.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                checkpoint.integrity().getBytes(java.nio.charset.StandardCharsets.US_ASCII))) {
            throw new FileCheckpointException("file checkpoint integrity verification failed");
        }
        new ToolPermissionRequest(
                checkpoint.checkpointId(),
                checkpoint.runId(),
                projectRoot,
                checkpoint.capability(),
                checkpoint.permissionTarget());
        List<Path> roots = normalizeRoots(checkpoint.roots().stream().map(Path::of).toList());
        validateRootShape(checkpoint.operation(), roots);
        validateSafeRoots(roots);
        validateTarget(checkpoint.operation(), checkpoint.permissionTarget(), roots);

        if (checkpoint.snapshots().size() > MAX_FILES) {
            throw new FileCheckpointException("file checkpoint exceeds " + MAX_FILES + " entries");
        }
        Set<Path> snapshotPaths = new HashSet<>();
        Set<Path> rootSnapshots = new HashSet<>();
        long bytes = 0;
        for (FileSnapshot snapshot : checkpoint.snapshots()) {
            Path path = normalizedAbsolute(snapshot.path(), "snapshot path");
            if (!snapshotPaths.add(path)) {
                throw new FileCheckpointException("duplicate snapshot path: " + path);
            }
            Path containingRoot = roots.stream()
                    .filter(path::startsWith)
                    .findFirst()
                    .orElseThrow(() -> new FileCheckpointException(
                            "snapshot escapes checkpoint roots: " + path));
            if (path.equals(containingRoot)) {
                rootSnapshots.add(path);
            } else if (snapshot.type() == SnapshotType.MISSING) {
                throw new FileCheckpointException("only a checkpoint root may be missing");
            }
            bytes += snapshot.content().length;
            if (bytes > MAX_BYTES) {
                throw new FileCheckpointException("file checkpoint exceeds " + MAX_BYTES + " bytes");
            }
            validatePermissions(snapshot.posixPermissions());
            Objects.requireNonNull(snapshot.dosAttributes(), "DOS attributes");
        }
        if (!rootSnapshots.containsAll(roots)) {
            throw new FileCheckpointException("every checkpoint root needs an exact snapshot");
        }
        validateCapability(checkpoint, roots);
    }

    private void validateBinding(
            FileToolRequest operation,
            ToolPermissionRequest permission,
            List<Path> roots) {
        if (!permission.operationId().equals(operation.operationId())
                || !permission.runId().equals(runId)
                || !permission.projectRoot().equals(projectRoot.toString())) {
            throw new FileCheckpointException("checkpoint request is not bound to this project and run");
        }
        validateSafeRoots(roots);
        validateTarget(operation.operation(), permission.target(), roots);
    }

    private void validateSameRequest(
            FileCheckpoint existing,
            FileToolRequest operation,
            ToolPermissionRequest permission,
            List<Path> roots) {
        if (existing.operation() != operation.operation()
                || existing.capability() != permission.capability()
                || !existing.permissionTarget().equals(permission.target())
                || !existing.requestFingerprint().equals(requestFingerprint(operation, roots))
                || !existing.roots().equals(roots.stream().map(Path::toString).toList())) {
            throw new FileCheckpointException(
                    "operationId is already bound to a different immutable file checkpoint");
        }
    }

    private void validateCapability(FileCheckpoint checkpoint, List<Path> roots) {
        ToolCapability expected;
        if (roots.stream().anyMatch(root -> !root.startsWith(projectRoot))) {
            expected = ToolCapability.OUTSIDE_PROJECT_ACCESS;
        } else {
            expected = switch (checkpoint.operation()) {
                case WRITE, CREATE_DIRECTORY, MOVE -> ToolCapability.PROJECT_WRITE;
                case DELETE -> rootSnapshot(checkpoint, roots.getFirst()).type() == SnapshotType.DIRECTORY
                        ? ToolCapability.BULK_DELETE
                        : ToolCapability.PROJECT_DELETE;
                case READ -> throw new FileCheckpointException("read operations must not create checkpoints");
            };
        }
        if (checkpoint.capability() != expected) {
            throw new FileCheckpointException("checkpoint capability does not match its paths and operation");
        }
    }

    private static FileSnapshot rootSnapshot(FileCheckpoint checkpoint, Path root) {
        return checkpoint.snapshots().stream()
                .filter(snapshot -> Path.of(snapshot.path()).equals(root))
                .findFirst()
                .orElseThrow(() -> new FileCheckpointException("checkpoint root snapshot is missing"));
    }

    private void validateSafeRoots(List<Path> roots) {
        for (Path root : roots) {
            if (root.equals(projectRoot)
                    || FilePathPolicy.touchesProjectDirectory(projectRoot, root, ".oml")
                    || FilePathPolicy.touchesProjectDirectory(projectRoot, root, ".git")
                    || FilePathPolicy.isSensitive(root)
                    || FilePathPolicy.isProtectedSystemPath(root)) {
                throw new FileCheckpointException("unsafe checkpoint root: " + root);
            }
            rejectSymlinkComponents(root);
        }
    }

    private static void validateRootShape(FileOperation operation, List<Path> roots) {
        int expected = operation == FileOperation.MOVE ? 2 : 1;
        if (operation == FileOperation.READ || roots.size() != expected) {
            throw new FileCheckpointException("checkpoint roots do not match the file operation");
        }
        for (int left = 0; left < roots.size(); left++) {
            for (int right = left + 1; right < roots.size(); right++) {
                Path first = roots.get(left);
                Path second = roots.get(right);
                if (first.startsWith(second) || second.startsWith(first)) {
                    throw new FileCheckpointException("checkpoint roots must not overlap");
                }
            }
        }
    }

    private static void validateTarget(FileOperation operation, String target, List<Path> roots) {
        String prefix = operation.name().toLowerCase(java.util.Locale.ROOT) + ":";
        String expected = roots.size() == 1
                ? prefix + roots.getFirst()
                : prefix + roots.get(0) + "->" + roots.get(1);
        if (!expected.equals(target)) {
            throw new FileCheckpointException("checkpoint permission target does not match its roots");
        }
    }

    private static List<Path> normalizeRoots(List<Path> roots) {
        Objects.requireNonNull(roots, "roots");
        List<Path> normalized = new ArrayList<>();
        for (Path root : roots) {
            normalized.add(normalizedAbsolute(
                    Objects.requireNonNull(root, "checkpoint path").toString(),
                    "checkpoint path"));
        }
        if (normalized.isEmpty() || new LinkedHashSet<>(normalized).size() != normalized.size()) {
            throw new FileCheckpointException("checkpoint roots must be non-empty and unique");
        }
        return List.copyOf(normalized);
    }

    private static Path normalizedAbsolute(String value, String name) {
        Objects.requireNonNull(value, name);
        Path original = Path.of(value);
        Path normalized = original.toAbsolutePath().normalize();
        if (!original.isAbsolute() || !original.equals(normalized)) {
            throw new FileCheckpointException(name + " must be an absolute normalized path");
        }
        return normalized;
    }

    private static String requestFingerprint(FileToolRequest operation, List<Path> roots) {
        String contentHash = ToolNodeSupport.fingerprint(
                java.util.HexFormat.of().formatHex(operation.content()));
        return ToolNodeSupport.fingerprint(operation.operation().name()
                + "\0" + roots.stream().map(Path::toString).toList()
                + "\0" + contentHash);
    }

    private String encode(FileCheckpoint checkpoint) {
        try {
            return mapper.writeValueAsString(checkpoint);
        } catch (JsonProcessingException error) {
            throw new FileCheckpointException("failed to encode file checkpoint", error);
        }
    }

    private FileCheckpoint withIntegrity(FileCheckpoint source, String integrity) {
        return new FileCheckpoint(
                source.schemaVersion(),
                source.checkpointId(),
                source.projectRoot(),
                source.runId(),
                source.operation(),
                source.capability(),
                source.permissionTarget(),
                source.requestFingerprint(),
                source.roots(),
                source.snapshots(),
                integrity);
    }

    private String sign(FileCheckpoint checkpoint, boolean createKey) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(integrityKey(createKey), "HmacSHA256"));
            byte[] payload = mapper.writeValueAsBytes(checkpoint);
            return java.util.HexFormat.of().formatHex(mac.doFinal(payload));
        } catch (java.security.GeneralSecurityException | JsonProcessingException error) {
            throw new FileCheckpointException("failed to verify checkpoint integrity", error);
        }
    }

    private byte[] integrityKey(boolean create) {
        Path oml = projectRoot.resolve(".oml");
        Path keyPath = oml.resolve(INTEGRITY_KEY_FILE);
        try {
            if (Files.isSymbolicLink(oml) || Files.isSymbolicLink(keyPath)) {
                throw new FileCheckpointException("checkpoint integrity key path must not be a symbolic link");
            }
            if (Files.notExists(keyPath, LinkOption.NOFOLLOW_LINKS)) {
                if (!create) {
                    throw new FileCheckpointException("checkpoint integrity key is missing");
                }
                Files.createDirectories(oml);
                byte[] generated = new byte[INTEGRITY_KEY_BYTES];
                new SecureRandom().nextBytes(generated);
                try {
                    Files.write(keyPath, generated, StandardOpenOption.CREATE_NEW, LinkOption.NOFOLLOW_LINKS);
                    restrictToCurrentUser(keyPath);
                    return generated;
                } catch (FileAlreadyExistsException ignored) {
                    // Another trusted OML process won key creation; read that durable key below.
                }
            }
            byte[] existing = Files.readAllBytes(keyPath);
            if (existing.length != INTEGRITY_KEY_BYTES) {
                throw new FileCheckpointException("checkpoint integrity key has an invalid size");
            }
            return existing;
        } catch (IOException error) {
            throw new FileCheckpointException("failed to load checkpoint integrity key", error);
        }
    }

    private static void restrictToCurrentUser(Path path) throws IOException {
        try {
            Files.setPosixFilePermissions(path, Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException ignored) {
            // Native ACL inheritance is the non-POSIX fallback.
        }
    }

    private FileCheckpoint decode(String json) {
        try {
            return mapper.readValue(json, FileCheckpoint.class);
        } catch (JsonProcessingException | IllegalArgumentException error) {
            throw new FileCheckpointException("failed to decode file checkpoint", error);
        }
    }

    private static String readBounded(Path path) {
        try {
            long size = Files.size(path);
            if (size > MAX_MANIFEST_BYTES) {
                throw new FileCheckpointException("file checkpoint manifest is too large");
            }
            return Files.readString(path);
        } catch (IOException error) {
            throw new FileCheckpointException("failed to read file checkpoint: " + path, error);
        }
    }

    private void writeAtomicallyNew(Path target, String content) {
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
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
                temporary = null;
            } catch (FileAlreadyExistsException error) {
                throw new CheckpointAlreadyExists();
            } catch (AtomicMoveNotSupportedException error) {
                throw new FileCheckpointException("atomic checkpoint creation is unavailable", error);
            }
        } catch (CheckpointAlreadyExists error) {
            throw error;
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

    private <T> T withCheckpointLock(String checkpointId, Supplier<T> action) {
        Path lockPath = checkpointRoot.resolve(validateId(checkpointId, "checkpointId") + ".lock");
        return withFileLock(lockPath, action);
    }

    private <T> T withFileLock(Path lockPath, Supplier<T> action) {
        Path normalizedLock = lockPath.toAbsolutePath().normalize();
        Set<Path> held = HELD_LOCKS.get();
        if (!held.add(normalizedLock)) {
            return action.get();
        }
        Object jvmLock = JVM_LOCKS.computeIfAbsent(lockPath, ignored -> new Object());
        try {
            synchronized (jvmLock) {
                try {
                    createCheckpointDirectory();
                    if (Files.isSymbolicLink(lockPath)) {
                        throw new FileCheckpointException("file tool lock must not be a symbolic link");
                    }
                    try (FileChannel channel = FileChannel.open(
                                    lockPath,
                                    StandardOpenOption.CREATE,
                                    StandardOpenOption.WRITE,
                                    LinkOption.NOFOLLOW_LINKS);
                            java.nio.channels.FileLock ignored = channel.lock()) {
                        restrictToCurrentUser(lockPath);
                        return action.get();
                    }
                } catch (IOException error) {
                    throw new FileCheckpointException("failed to lock file tool state", error);
                }
            }
        } finally {
            held.remove(normalizedLock);
            if (held.isEmpty()) {
                HELD_LOCKS.remove();
            }
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

    private static List<String> readPermissions(Path path) {
        try {
            return Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS).stream()
                    .map(PosixFilePermission::name)
                    .sorted()
                    .toList();
        } catch (IOException | UnsupportedOperationException ignored) {
            return List.of();
        }
    }

    private static void applyPermissions(Path path, List<String> names) {
        if (names.isEmpty()) {
            return;
        }
        Set<PosixFilePermission> permissions = new HashSet<>();
        names.forEach(name -> permissions.add(PosixFilePermission.valueOf(name)));
        try {
            Files.setPosixFilePermissions(path, permissions);
        } catch (IOException | UnsupportedOperationException error) {
            throw new FileCheckpointException("failed to restore file permissions: " + path, error);
        }
    }

    private static void applyMetadata(Path path, FileSnapshot snapshot) {
        applyPermissions(path, snapshot.posixPermissions());
        DosSnapshot attributes = snapshot.dosAttributes();
        if (!attributes.present()) {
            return;
        }
        DosFileAttributeView view = Files.getFileAttributeView(
                path,
                DosFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS);
        if (view == null) {
            throw new FileCheckpointException("DOS attributes cannot be restored on this filesystem: " + path);
        }
        try {
            view.setArchive(attributes.archive());
            view.setHidden(attributes.hidden());
            view.setSystem(attributes.system());
            view.setReadOnly(attributes.readOnly());
        } catch (IOException error) {
            throw new FileCheckpointException("failed to restore DOS attributes: " + path, error);
        }
    }

    private static DosSnapshot readDosAttributes(Path path) {
        try {
            DosFileAttributes attributes = Files.readAttributes(
                    path,
                    DosFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            return new DosSnapshot(
                    true,
                    attributes.isReadOnly(),
                    attributes.isHidden(),
                    attributes.isSystem(),
                    attributes.isArchive());
        } catch (IOException | UnsupportedOperationException ignored) {
            return DosSnapshot.unsupported();
        }
    }

    private static void validatePermissions(List<String> names) {
        Set<String> unique = new HashSet<>();
        for (String name : names) {
            try {
                PosixFilePermission.valueOf(name);
            } catch (IllegalArgumentException error) {
                throw new FileCheckpointException("invalid POSIX permission in checkpoint", error);
            }
            if (!unique.add(name)) {
                throw new FileCheckpointException("duplicate POSIX permission in checkpoint");
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

    record FileCheckpoint(
            int schemaVersion,
            String checkpointId,
            String projectRoot,
            String runId,
            FileOperation operation,
            ToolCapability capability,
            String permissionTarget,
            String requestFingerprint,
            List<String> roots,
            List<FileSnapshot> snapshots,
            String integrity) {
        FileCheckpoint {
            checkpointId = validateId(checkpointId, "checkpointId");
            projectRoot = Objects.requireNonNull(projectRoot, "projectRoot");
            runId = validateId(runId, "runId");
            Objects.requireNonNull(operation, "operation");
            Objects.requireNonNull(capability, "capability");
            permissionTarget = Objects.requireNonNull(permissionTarget, "permissionTarget");
            requestFingerprint = Objects.requireNonNull(requestFingerprint, "requestFingerprint");
            roots = List.copyOf(Objects.requireNonNull(roots, "roots"));
            snapshots = List.copyOf(Objects.requireNonNull(snapshots, "snapshots"));
            integrity = Objects.requireNonNull(integrity, "integrity");
        }
    }

    record FileSnapshot(
            String path,
            SnapshotType type,
            byte[] content,
            List<String> posixPermissions,
            DosSnapshot dosAttributes) {
        FileSnapshot {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(type, "type");
            content = content == null ? new byte[0] : java.util.Arrays.copyOf(content, content.length);
            posixPermissions = posixPermissions == null ? List.of() : List.copyOf(posixPermissions);
            dosAttributes = dosAttributes == null ? DosSnapshot.unsupported() : dosAttributes;
            if (type != SnapshotType.FILE && content.length > 0) {
                throw new IllegalArgumentException(type + " snapshot must not contain bytes");
            }
            if (type == SnapshotType.MISSING
                    && (!posixPermissions.isEmpty() || dosAttributes.present())) {
                throw new IllegalArgumentException("missing snapshot must not contain metadata");
            }
        }

        @Override
        public byte[] content() {
            return java.util.Arrays.copyOf(content, content.length);
        }
    }

    enum SnapshotType {
        MISSING,
        DIRECTORY,
        FILE
    }

    record DosSnapshot(boolean present, boolean readOnly, boolean hidden, boolean system, boolean archive) {
        static DosSnapshot unsupported() {
            return new DosSnapshot(false, false, false, false, false);
        }
    }

    private static final class SnapshotCollector {
        private final List<FileSnapshot> snapshots = new ArrayList<>();
        private final Set<Path> seen = new HashSet<>();
        private long bytes;

        void capture(Path root) {
            Path normalized = root.toAbsolutePath().normalize();
            rejectSymlinkComponents(normalized);
            if (Files.notExists(normalized, LinkOption.NOFOLLOW_LINKS)) {
                add(new FileSnapshot(
                        normalized.toString(),
                        SnapshotType.MISSING,
                        null,
                        List.of(),
                        DosSnapshot.unsupported()));
                return;
            }
            try {
                Files.walkFileTree(normalized, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                        addSnapshot(directory, SnapshotType.DIRECTORY, null);
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                        if (attributes.isSymbolicLink() || !attributes.isRegularFile()) {
                            throw new FileCheckpointException("unsupported file type in checkpoint: " + file);
                        }
                        long size = attributes.size();
                        if (size < 0 || size > MAX_BYTES - bytes) {
                            throw new FileCheckpointException("file checkpoint exceeds " + MAX_BYTES + " bytes");
                        }
                        byte[] content = Files.readAllBytes(file);
                        if (content.length != size) {
                            throw new FileCheckpointException("file changed while its checkpoint was captured: " + file);
                        }
                        bytes += content.length;
                        addSnapshot(file, SnapshotType.FILE, content);
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException error) {
                throw new FileCheckpointException("failed to capture path: " + normalized, error);
            }
        }

        private void addSnapshot(Path path, SnapshotType type, byte[] content) {
            Path normalized = path.toAbsolutePath().normalize();
            if (!seen.add(normalized)) {
                throw new FileCheckpointException("duplicate snapshot path: " + normalized);
            }
            add(new FileSnapshot(
                    normalized.toString(),
                    type,
                    content,
                    readPermissions(path),
                    readDosAttributes(path)));
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

    private static final class CheckpointAlreadyExists extends RuntimeException {}
}
