package io.ohmyluke.tool;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/** Disposable project copy that prevents process writes from touching the user's checkout. */
public final class ProcessWorkspace implements AutoCloseable {
    private static final int MAX_FILES = 20_000;
    private static final long MAX_BYTES = 512L * 1024 * 1024;
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");
    private static final Set<String> EXCLUDED_DIRECTORIES = Set.of(
            ".git", ".oml", ".gradle", ".idea", "build", "target", "node_modules", "dist", "out");

    private final Path temporaryRoot;
    private final Path sourceRoot;
    private final Path projectRoot;
    private final Path isolatedHome;

    private ProcessWorkspace(Path temporaryRoot, Path sourceRoot) {
        this.temporaryRoot = temporaryRoot;
        this.sourceRoot = sourceRoot;
        this.projectRoot = temporaryRoot.resolve("project");
        this.isolatedHome = temporaryRoot.resolve("home");
    }

    public static ProcessWorkspace create(Path projectRoot, String runId, String operationId) {
        validateId(runId, "runId");
        validateId(operationId, "operationId");
        try {
            Path realRoot = Objects.requireNonNull(projectRoot, "projectRoot").toRealPath();
            Path temporary = Files.createTempDirectory(
                    isolatedTemporaryBase(),
                    "oml-" + runId + "-" + operationId + "-");
            ProcessWorkspace workspace = new ProcessWorkspace(temporary, realRoot);
            Files.createDirectory(workspace.projectRoot);
            Files.createDirectory(workspace.isolatedHome);
            workspace.copyProject();
            return workspace;
        } catch (IOException error) {
            throw new ProcessToolException("failed to create isolated process workspace", error);
        }
    }

    public Path projectRoot() {
        return projectRoot;
    }

    public Path isolatedHome() {
        return isolatedHome;
    }

    public Path workingDirectory(Path requested) {
        Objects.requireNonNull(requested, "requested");
        for (Path part : requested) {
            if (part.toString().equals("..")) {
                throw new ProcessToolException("process working directory must not contain parent traversal");
            }
        }
        Path relative;
        if (requested.isAbsolute()) {
            Path normalized = requested.toAbsolutePath().normalize();
            if (!normalized.startsWith(sourceRoot)) {
                throw new ProcessToolException("process working directory is outside the project");
            }
            relative = sourceRoot.relativize(normalized);
        } else {
            relative = requested.normalize();
        }
        Path mapped = projectRoot.resolve(relative).normalize();
        if (!mapped.startsWith(projectRoot) || !Files.isDirectory(mapped, LinkOption.NOFOLLOW_LINKS)) {
            throw new ProcessToolException("process working directory does not exist in the isolated workspace");
        }
        return mapped;
    }

    public Path mapExecutable(Path executable) {
        Path normalized = executable.toAbsolutePath().normalize();
        if (normalized.startsWith(sourceRoot)) {
            Path mapped = projectRoot.resolve(sourceRoot.relativize(normalized));
            if (!Files.isRegularFile(mapped, LinkOption.NOFOLLOW_LINKS)) {
                throw new ProcessToolException("project executable was excluded from the isolated workspace");
            }
            return mapped;
        }
        try {
            return normalized.toRealPath();
        } catch (IOException error) {
            throw new ProcessToolException("executable must exist and resolve safely", error);
        }
    }

    @Override
    public void close() {
        try {
            FileCheckpointStore.deleteTree(temporaryRoot);
        } catch (RuntimeException ignored) {
            // Workspace cleanup failure cannot change the already bounded process result.
        }
    }

    private void copyProject() throws IOException {
        AtomicInteger files = new AtomicInteger();
        AtomicLong bytes = new AtomicLong();
        Files.walkFileTree(sourceRoot, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                if (!directory.equals(sourceRoot) && excludedDirectory(directory.getFileName().toString())) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                Path target = projectRoot.resolve(sourceRoot.relativize(directory));
                if (!target.equals(projectRoot)) {
                    Files.createDirectory(target);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                if (attributes.isSymbolicLink()) {
                    return FileVisitResult.CONTINUE;
                }
                if (!attributes.isRegularFile() || sensitive(file)) {
                    return FileVisitResult.CONTINUE;
                }
                if (files.incrementAndGet() > MAX_FILES || bytes.addAndGet(attributes.size()) > MAX_BYTES) {
                    throw new ProcessToolException("project copy exceeds the isolated workspace limit");
                }
                Path target = projectRoot.resolve(sourceRoot.relativize(file));
                Files.copy(file, target, StandardCopyOption.COPY_ATTRIBUTES);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static boolean excludedDirectory(String name) {
        return EXCLUDED_DIRECTORIES.contains(name.toLowerCase(Locale.ROOT));
    }

    private static boolean sensitive(Path path) {
        String name = path.getFileName() == null
                ? ""
                : path.getFileName().toString().toLowerCase(Locale.ROOT);
        String normalized = path.toString().replace('\\', '/').toLowerCase(Locale.ROOT);
        if (name.equals(".env.example") || name.equals(".env.template")) {
            return false;
        }
        return name.equals(".env")
                || name.startsWith(".env.")
                || name.equals(".npmrc")
                || name.equals(".netrc")
                || name.equals(".pypirc")
                || name.equals("settings.xml")
                || name.equals("credentials.json")
                || name.equals("service-account.json")
                || name.equals("docker-config.json")
                || name.endsWith(".pem")
                || name.endsWith(".key")
                || name.endsWith(".p12")
                || name.endsWith(".pfx")
                || name.equals("id_rsa")
                || name.equals("id_ed25519")
                || normalized.contains("/.ssh/")
                || normalized.contains("/.aws/")
                || normalized.contains("/.m2/")
                || normalized.contains("/.config/gh/")
                || normalized.contains("/.config/gcloud/")
                || normalized.contains("/.azure/")
                || normalized.contains("/.codex/")
                || normalized.contains("/.claude/");
    }

    private static void validateId(String value, String name) {
        Objects.requireNonNull(value, name);
        if (!SAFE_ID.matcher(value).matches()) {
            throw new IllegalArgumentException("invalid " + name + ": " + value);
        }
    }

    private static Path isolatedTemporaryBase() throws IOException {
        String operatingSystem = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        Path base = operatingSystem.contains("mac") && Files.isDirectory(Path.of("/private/tmp"))
                ? Path.of("/private/tmp")
                : Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize();
        if (!Files.isDirectory(base) || !Files.isWritable(base)) {
            throw new IOException("isolated temporary base is unavailable: " + base);
        }
        return base;
    }
}
