package io.ohmyluke.policy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Clock;
import java.util.Objects;

/** Fail-closed path validation for one configured project root. */
public final class ProjectBoundaryPolicy {
    private final Path configuredRoot;
    private final Path projectRoot;
    private final String runId;
    private final Clock clock;

    public ProjectBoundaryPolicy(Path projectRoot) {
        this(projectRoot, "unscoped", Clock.systemUTC());
    }

    public ProjectBoundaryPolicy(Path projectRoot, String runId, Clock clock) {
        Objects.requireNonNull(projectRoot, "projectRoot");
        Objects.requireNonNull(runId, "runId");
        if (runId.isBlank()) {
            throw new IllegalArgumentException("runId must not be blank");
        }
        this.runId = runId;
        this.clock = Objects.requireNonNull(clock, "clock");
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
        return evaluate(requestedPath, access, null);
    }

    public PolicyDecision evaluate(
            Path requestedPath,
            FileAccess access,
            FileApprovalGrant approval) {
        Objects.requireNonNull(requestedPath, "requestedPath");
        Objects.requireNonNull(access, "access");
        for (Path part : requestedPath) {
            if (part.toString().equals("..")) {
                return blocked("boundary.parent-traversal", "Parent path traversal is not allowed");
            }
        }

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
            BasicFileAttributes attributes;
            try {
                attributes = Files.readAttributes(
                        inspected,
                        BasicFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS);
            } catch (NoSuchFileException missing) {
                break;
            } catch (IOException unresolved) {
                return blocked("boundary.unresolved", "Path component could not be inspected safely");
            }
            if (!attributes.isSymbolicLink()) {
                continue;
            }

            boolean finalComponent = index == relative.getNameCount();
            if (finalComponent) {
                return blocked(
                        "boundary.final-symlink",
                        "Access through a final symbolic link is not allowed");
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

        if (access == FileAccess.DELETE && !isApproved(approval, relative, access)) {
            return new PolicyDecision(
                    PolicyOutcome.BLOCKED,
                    "boundary.approval-required",
                    "Deleting an exact project path requires an operation-bound user approval",
                    true);
        }

        return PolicyDecision.continueExecution(
                "boundary.allowed",
                "Requested path stays inside the configured project root");
    }

    private static PolicyDecision blocked(String code, String detail) {
        return new PolicyDecision(PolicyOutcome.BLOCKED, code, detail, false);
    }

    private boolean isApproved(
            FileApprovalGrant approval,
            Path relative,
            FileAccess access) {
        return approval != null
                && approval.runId().equals(runId)
                && approval.projectRoot().equals(projectRoot.toString())
                && approval.relativePath().equals(relative.toString())
                && approval.access() == access
                && approval.expiresAtEpochMilli() > clock.millis();
    }
}
