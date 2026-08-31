package io.ohmyluke.tool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.regex.Pattern;

/** Writes bounded, already-redacted tool output under OML-owned run artifacts. */
public final class ToolArtifactStore {
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

    private final Path projectRoot;
    private final String runId;

    public ToolArtifactStore(Path projectRoot, String runId) {
        try {
            this.projectRoot = Objects.requireNonNull(projectRoot, "projectRoot").toRealPath();
        } catch (IOException error) {
            throw new ProcessToolException("failed to resolve artifact project root", error);
        }
        this.runId = validateId(runId, "runId");
    }

    public String store(String operationId, String name, byte[] content) {
        String safeOperation = validateId(operationId, "operationId");
        String safeName = validateId(name, "artifact name");
        Objects.requireNonNull(content, "content");
        Path directory = projectRoot
                .resolve(".oml/runs")
                .resolve(runId)
                .resolve("artifacts")
                .resolve(safeOperation);
        Path target = directory.resolve(safeName);
        Path temporary = null;
        try {
            rejectSymlinks(target);
            Path oml = projectRoot.resolve(".oml");
            if (Files.isSymbolicLink(oml)) {
                throw new ProcessToolException(".oml must not be a symbolic link");
            }
            Files.createDirectories(directory);
            if (!directory.toRealPath().startsWith(projectRoot)) {
                throw new ProcessToolException("artifact directory escapes the project");
            }
            temporary = Files.createTempFile(directory, safeName + ".", ".tmp");
            Files.write(temporary, content, LinkOption.NOFOLLOW_LINKS);
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            return projectRoot.relativize(target).toString();
        } catch (IOException error) {
            throw new ProcessToolException("failed to store tool artifact", error);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // Temporary artifacts are never referenced from graph state.
                }
            }
        }
    }

    /** Reads only a named OML artifact with a strict byte bound and no symbolic-link traversal. */
    public byte[] read(String operationId, String name, int maxBytes) {
        if (maxBytes < 1 || maxBytes > 16 * 1024 * 1024) {
            throw new IllegalArgumentException("invalid artifact read limit");
        }
        Path target = projectRoot.resolve(".oml/runs").resolve(runId).resolve("artifacts")
                .resolve(validateId(operationId, "operationId")).resolve(validateId(name, "artifact name"));
        rejectSymlinks(target);
        try (var input = Files.newInputStream(target, LinkOption.NOFOLLOW_LINKS)) {
            byte[] content = input.readNBytes(maxBytes + 1);
            if (content.length > maxBytes) {
                throw new ProcessToolException("artifact exceeds read limit");
            }
            return content;
        } catch (IOException error) {
            throw new ProcessToolException("failed to read tool artifact", error);
        }
    }

    private void rejectSymlinks(Path target) {
        Path current = projectRoot;
        for (Path part : projectRoot.relativize(target)) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) {
                throw new ProcessToolException("artifact paths must not contain symbolic links");
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
}
