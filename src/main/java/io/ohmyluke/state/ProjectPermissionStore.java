package io.ohmyluke.state;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/** Atomic `.oml/permissions.json` persistence used only by the trusted OML process. */
public final class ProjectPermissionStore {
    private static final String FILE_NAME = "permissions.json";
    private static final String LOCK_FILE_NAME = "permissions.lock";
    private static final ConcurrentHashMap<Path, Object> JVM_LOCKS = new ConcurrentHashMap<>();

    private final Path projectRoot;
    private final Path path;
    private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    public ProjectPermissionStore(Path projectRoot) {
        this.projectRoot = RunFileSupport.normalizeRoot(projectRoot);
        this.path = this.projectRoot.resolve(".oml").resolve(FILE_NAME);
    }

    public String projectRoot() {
        return projectRoot.toString();
    }

    public Path path() {
        return path;
    }

    /** Serializes permission read-modify-write transactions across threads and OML processes. */
    public <T> T withExclusiveLock(Supplier<T> action) {
        Objects.requireNonNull(action, "action");
        Path oml = path.getParent();
        Path lockPath = oml.resolve(LOCK_FILE_NAME);
        Object jvmLock = JVM_LOCKS.computeIfAbsent(lockPath, ignored -> new Object());
        synchronized (jvmLock) {
            try {
                prepareTrustedDirectory(oml);
                if (Files.isSymbolicLink(lockPath)) {
                    throw new CheckpointException("permission lock must not be a symbolic link");
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
                throw new CheckpointException("failed to lock permission settings", error);
            }
        }
    }

    public ProjectPermissionSettings load() {
        if (Files.notExists(path, LinkOption.NOFOLLOW_LINKS)) {
            return ProjectPermissionSettings.defaults(projectRoot());
        }
        if (Files.isSymbolicLink(path)) {
            throw new CheckpointException("permission settings must not be a symbolic link");
        }
        try {
            JsonNode root = mapper.readTree(Files.readString(path));
            if (root == null || !root.isObject()) {
                throw new CheckpointException("permission settings must be a JSON object");
            }
            JsonNode version = root.get("schemaVersion");
            if (version == null || version.intValue() != ProjectPermissionSettings.CURRENT_SCHEMA_VERSION) {
                throw new CheckpointException("unsupported permission settings schemaVersion");
            }
            ProjectPermissionSettings settings = mapper.treeToValue(root, ProjectPermissionSettings.class);
            validate(settings);
            return settings;
        } catch (JsonProcessingException error) {
            throw new CheckpointException("failed to decode permission settings", error);
        } catch (IOException error) {
            throw new CheckpointException("failed to read permission settings", error);
        }
    }

    public void save(ProjectPermissionSettings settings) {
        Objects.requireNonNull(settings, "settings");
        validate(settings);
        try {
            Path oml = path.getParent();
            prepareTrustedDirectory(oml);
            RunFileSupport.writeAtomically(path, mapper.writeValueAsString(settings));
            restrictToCurrentUser(path);
        } catch (JsonProcessingException error) {
            throw new CheckpointException("failed to encode permission settings", error);
        } catch (IOException error) {
            throw new CheckpointException("failed to protect permission settings", error);
        }
    }

    private void prepareTrustedDirectory(Path oml) throws IOException {
        if (Files.isSymbolicLink(oml)) {
            throw new CheckpointException(".oml must not be a symbolic link");
        }
        Files.createDirectories(oml);
        if (!oml.toRealPath().startsWith(projectRoot)) {
            throw new CheckpointException("permission settings path escapes the project");
        }
    }

    private void validate(ProjectPermissionSettings settings) {
        if (!settings.projectRoot().equals(projectRoot())) {
            throw new CheckpointException("permission settings belong to a different project");
        }
        for (io.ohmyluke.policy.ToolPermissionGrant grant : settings.grants()) {
            if (!grant.projectRoot().equals(projectRoot())) {
                throw new CheckpointException("permission grant belongs to a different project");
            }
        }
    }

    private static void restrictToCurrentUser(Path path) throws IOException {
        try {
            Files.setPosixFilePermissions(path, Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException ignored) {
            // Windows ACL inheritance remains the platform fallback until its native adapter is added.
        }
    }
}
