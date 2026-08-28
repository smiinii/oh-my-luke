package io.ohmyluke.policy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Objects;

/** Fail-closed path validation for one configured project root. */
public final class ProjectBoundaryPolicy {
    private final Path configuredRoot;
    private final Path projectRoot;

    public ProjectBoundaryPolicy(Path projectRoot) {
        Objects.requireNonNull(projectRoot, "projectRoot");
        this.configuredRoot = projectRoot.toAbsolutePath().normalize();
        try {
            this.projectRoot = projectRoot.toRealPath();
        } catch (IOException exception) {
            throw new IllegalArgumentException("projectRoot must exist and be resolvable", exception);
        }
        if (!Files.isDirectory(this.projectRoot)) {
            throw new IllegalArgumentException("projectRoot must be a directory");
        }
    }

    public Path projectRoot() {
        return projectRoot;
    }

    public PolicyDecision evaluate(Path requestedPath, FileAccess access) {
        Objects.requireNonNull(requestedPath, "requestedPath");
        Objects.requireNonNull(access, "access");

        Path absolute = requestedPath.isAbsolute()
                ? requestedPath.toAbsolutePath().normalize()
                : configuredRoot.resolve(requestedPath).normalize();
        Path relative;
        if (absolute.startsWith(configuredRoot)) {
            relative = configuredRoot.relativize(absolute);
        } else if (absolute.startsWith(projectRoot)) {
            relative = projectRoot.relativize(absolute);
        } else {
            return blocked("boundary.outside", "Requested path is outside the configured project root");
        }

        Path inspected = projectRoot;
        int index = 0;
        for (Path part : relative) {
            index++;
            inspected = inspected.resolve(part);
            if (!Files.exists(inspected, LinkOption.NOFOLLOW_LINKS)) {
                break;
            }
            if (!Files.isSymbolicLink(inspected)) {
                continue;
            }

            boolean finalComponent = index == relative.getNameCount();
            if (finalComponent && access != FileAccess.READ) {
                return blocked(
                        "boundary.final-symlink",
                        "Writing or deleting through a final symbolic link is not allowed");
            }

            try {
                inspected = inspected.toRealPath();
            } catch (IOException exception) {
                return blocked("boundary.unresolved", "Symbolic link target could not be resolved safely");
            }
            if (!inspected.startsWith(projectRoot)) {
                return blocked("boundary.symlink-escape", "Symbolic link escapes the configured project root");
            }
        }

        return PolicyDecision.continueExecution(
                "boundary.allowed",
                "Requested path stays inside the configured project root");
    }

    private static PolicyDecision blocked(String code, String detail) {
        return new PolicyDecision(PolicyOutcome.BLOCKED, code, detail, false);
    }
}
