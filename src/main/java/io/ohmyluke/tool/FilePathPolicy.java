package io.ohmyluke.tool;

import io.ohmyluke.policy.ToolCapability;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Classifies exact file targets and rejects symlink, secret, OML, and protected-system access. */
final class FilePathPolicy {
    private static final Set<String> SECRET_NAMES = Set.of(
            ".env", "id_rsa", "id_ed25519", "credentials", "hosts.yml");
    private static final List<String> SECRET_SUFFIXES = List.of(".pem", ".key", ".p12", ".pfx");

    private final Path configuredRoot;
    private final Path projectRoot;
    private final Path omlRoot;

    FilePathPolicy(Path projectRoot) {
        Objects.requireNonNull(projectRoot, "projectRoot");
        this.configuredRoot = projectRoot.toAbsolutePath().normalize();
        try {
            this.projectRoot = projectRoot.toRealPath();
        } catch (IOException error) {
            throw new IllegalArgumentException("projectRoot must exist and be resolvable", error);
        }
        if (!Files.isDirectory(this.projectRoot)) {
            throw new IllegalArgumentException("projectRoot must be a directory");
        }
        this.omlRoot = this.projectRoot.resolve(".oml").normalize();
    }

    Path projectRoot() {
        return projectRoot;
    }

    Path resolve(Path requested) {
        Objects.requireNonNull(requested, "requested");
        for (Path part : requested) {
            if (part.toString().equals("..")) {
                throw deny("file.parent-traversal", "Parent traversal is not allowed");
            }
        }
        Path lexical = requested.isAbsolute()
                ? requested.toAbsolutePath().normalize()
                : configuredRoot.resolve(requested).normalize();
        Path absolute = lexical.startsWith(configuredRoot)
                ? projectRoot.resolve(configuredRoot.relativize(lexical)).normalize()
                : canonicalizeExistingAncestor(lexical);
        rejectExistingSymlinks(absolute);
        return absolute;
    }

    ToolCapability classify(FileToolRequest request, Path source, Path destination) {
        boolean mutation = request.operation() != FileOperation.READ;
        if (touchesOml(source) || (destination != null && touchesOml(destination))) {
            return ToolCapability.POLICY_MUTATION;
        }
        if (mutation && (source.equals(projectRoot)
                || touchesGit(source)
                || (destination != null && (destination.equals(projectRoot) || touchesGit(destination))))) {
            return ToolCapability.PROTECTED_SYSTEM_DAMAGE;
        }
        if (isSensitive(source) || (destination != null && isSensitive(destination))) {
            return ToolCapability.SECRET_DISCLOSURE;
        }
        if (mutation && (isProtectedSystemPath(source)
                || (destination != null && isProtectedSystemPath(destination)))) {
            return ToolCapability.PROTECTED_SYSTEM_DAMAGE;
        }
        if (!insideProject(source) || (destination != null && !insideProject(destination))) {
            return ToolCapability.OUTSIDE_PROJECT_ACCESS;
        }
        return switch (request.operation()) {
            case READ -> ToolCapability.PROJECT_READ;
            case WRITE, CREATE_DIRECTORY, MOVE -> ToolCapability.PROJECT_WRITE;
            case DELETE -> Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)
                    ? ToolCapability.BULK_DELETE
                    : ToolCapability.PROJECT_DELETE;
        };
    }

    String target(FileToolRequest request, Path source, Path destination) {
        String prefix = request.operation().name().toLowerCase(Locale.ROOT) + ":";
        return destination == null
                ? prefix + source
                : prefix + source + "->" + destination;
    }

    private boolean insideProject(Path path) {
        return path.startsWith(projectRoot);
    }

    private boolean touchesOml(Path path) {
        return path.startsWith(omlRoot) || startsWithProjectDirectory(path, ".oml");
    }

    private boolean touchesGit(Path path) {
        return startsWithProjectDirectory(path, ".git");
    }

    private boolean startsWithProjectDirectory(Path path, String directory) {
        if (!path.startsWith(projectRoot)) {
            return false;
        }
        Path relative = projectRoot.relativize(path);
        return relative.getNameCount() > 0
                && relative.getName(0).toString().equalsIgnoreCase(directory);
    }

    private static boolean isSensitive(Path path) {
        String normalized = path.toString().replace('\\', '/').toLowerCase(Locale.ROOT);
        String fileName = path.getFileName() == null
                ? ""
                : path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (fileName.equals(".env.example") || fileName.equals(".env.template")) {
            return false;
        }
        return SECRET_NAMES.contains(fileName)
                || (fileName.startsWith(".env.")
                        && !fileName.equals(".env.example")
                        && !fileName.equals(".env.template"))
                || SECRET_SUFFIXES.stream().anyMatch(fileName::endsWith)
                || normalized.contains("/.ssh/")
                || normalized.contains("/.aws/")
                || normalized.contains("/.config/gh/")
                || normalized.contains("/.config/codex/")
                || normalized.contains("/.config/openai/")
                || normalized.contains("/.codex/")
                || normalized.contains("/.claude/")
                || normalized.contains("/.azure/")
                || normalized.contains("/.config/gcloud/");
    }

    private static boolean isProtectedSystemPath(Path path) {
        String normalized = path.toAbsolutePath().normalize().toString().replace('\\', '/');
        String lower = normalized.toLowerCase(Locale.ROOT);
        if (lower.matches("^[a-z]:/windows(?:/.*)?$")
                || lower.matches("^[a-z]:/program files(?: \\(x86\\))?(?:/.*)?$")) {
            return true;
        }
        return lower.equals("/etc")
                || lower.startsWith("/etc/")
                || lower.equals("/system")
                || lower.startsWith("/system/")
                || lower.equals("/usr")
                || lower.startsWith("/usr/")
                || lower.equals("/bin")
                || lower.startsWith("/bin/")
                || lower.equals("/sbin")
                || lower.startsWith("/sbin/")
                || lower.equals("/private/etc")
                || lower.startsWith("/private/etc/");
    }

    private static void rejectExistingSymlinks(Path absolute) {
        Path root = absolute.getRoot();
        Path inspected = root;
        for (Path part : absolute) {
            inspected = inspected == null ? part : inspected.resolve(part);
            if (Files.isSymbolicLink(inspected)) {
                throw deny("file.symlink-deny", "Symbolic links are not accepted by the structured file tool");
            }
            if (Files.notExists(inspected, LinkOption.NOFOLLOW_LINKS)) {
                break;
            }
        }
    }

    private static Path canonicalizeExistingAncestor(Path absolute) {
        Path existing = absolute;
        while (existing != null && Files.notExists(existing, LinkOption.NOFOLLOW_LINKS)) {
            existing = existing.getParent();
        }
        if (existing == null) {
            throw deny("file.unresolved", "No existing ancestor could be resolved safely");
        }
        if (Files.isSymbolicLink(existing)) {
            throw deny("file.symlink-deny", "Symbolic links are not accepted by the structured file tool");
        }
        try {
            Path realExisting = existing.toRealPath();
            return realExisting.resolve(existing.relativize(absolute)).normalize();
        } catch (IOException error) {
            throw deny("file.unresolved", "The requested path could not be resolved safely");
        }
    }

    private static UnsafeFileRequestException deny(String reasonCode, String message) {
        return new UnsafeFileRequestException(reasonCode, message);
    }
}
