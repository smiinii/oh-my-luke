package io.ohmyluke.tool;

import io.ohmyluke.policy.ToolCapability;
import io.ohmyluke.policy.ToolPermission;
import io.ohmyluke.policy.ToolPermissionDecision;
import io.ohmyluke.policy.ToolPermissionEvaluator;
import io.ohmyluke.policy.ToolPermissionRequest;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** Executes structured, checkpointed file actions only after deterministic permission checks. */
public final class FileTool {
    private static final int MAX_CONTENT_BYTES = 8 * 1024 * 1024;

    private final String runId;
    private final ToolPermissionEvaluator permissions;
    private final FilePathPolicy paths;
    private final FileCheckpointStore checkpoints;

    public FileTool(
            Path projectRoot,
            String runId,
            ToolPermissionEvaluator permissions,
            java.time.Clock clock) {
        this.runId = requireText(runId, "runId");
        this.permissions = Objects.requireNonNull(permissions, "permissions");
        Objects.requireNonNull(clock, "clock");
        this.paths = new FilePathPolicy(projectRoot);
        this.checkpoints = new FileCheckpointStore(paths.projectRoot(), runId);
    }

    public ToolPermissionRequest permissionRequest(FileToolRequest request) {
        Objects.requireNonNull(request, "request");
        Path source = paths.resolve(request.path());
        Path destination = request.destination() == null ? null : paths.resolve(request.destination());
        rejectOverlappingMove(request, source, destination);
        ToolCapability capability = paths.classify(request, source, destination);
        return new ToolPermissionRequest(
                request.operationId(),
                runId,
                paths.projectRoot(),
                capability,
                paths.target(request, source, destination));
    }

    public FileToolResult execute(FileToolRequest request) {
        Objects.requireNonNull(request, "request");
        ToolPermissionRequest permissionRequest;
        try {
            permissionRequest = permissionRequest(request);
        } catch (UnsafeFileRequestException error) {
            return denied(error.reasonCode(), error.getMessage());
        }

        ToolPermissionDecision decision = permissions.evaluate(permissionRequest);
        if (decision.permission() != ToolPermission.ALLOW) {
            return new FileToolResult(decision, false, null, null, decision.detail());
        }

        Path source = paths.resolve(request.path());
        Path destination = request.destination() == null ? null : paths.resolve(request.destination());
        ToolPermissionRequest verifiedRequest;
        try {
            rejectOverlappingMove(request, source, destination);
            verifiedRequest = new ToolPermissionRequest(
                    request.operationId(),
                    runId,
                    paths.projectRoot(),
                    paths.classify(request, source, destination),
                    paths.target(request, source, destination));
        } catch (UnsafeFileRequestException | FileCheckpointException error) {
            return denied("file.changed-before-execution", error.getMessage());
        }
        if (!permissionRequest.equals(verifiedRequest)) {
            return denied(
                    "file.permission-scope-changed",
                    "The file target or risk class changed after approval; no mutation was performed");
        }
        return perform(request, source, destination, permissionRequest, decision);
    }

    public void restore(String checkpointId) {
        checkpoints.restore(checkpointId);
    }

    private FileToolResult perform(
            FileToolRequest request,
            Path source,
            Path destination,
            ToolPermissionRequest permissionRequest,
            ToolPermissionDecision decision) {
        if (request.operation() == FileOperation.READ) {
            return read(source, decision);
        }
        try {
            return checkpoints.withMutationLock(
                    () -> performMutation(request, source, destination, permissionRequest, decision));
        } catch (RuntimeException error) {
            return new FileToolResult(
                    decision,
                    false,
                    null,
                    null,
                    "File mutation lock failed safely: " + error.getClass().getSimpleName());
        }
    }

    private FileToolResult performMutation(
            FileToolRequest request,
            Path source,
            Path destination,
            ToolPermissionRequest permissionRequest,
            ToolPermissionDecision decision) {
        List<Path> roots = destination == null ? List.of(source) : List.of(source, destination);
        try {
            if (checkpoints.alreadyApplied(request, permissionRequest, roots)) {
                return new FileToolResult(
                        decision,
                        true,
                        null,
                        request.operationId(),
                        "File operation was already applied; the immutable result was reused");
            }
        } catch (RuntimeException error) {
            return new FileToolResult(
                    decision,
                    false,
                    null,
                    null,
                    "File operation replay validation failed safely: " + error.getClass().getSimpleName());
        }
        String checkpointId;
        try {
            checkpointId = checkpoints.capture(
                    request,
                    permissionRequest,
                    roots);
        } catch (RuntimeException error) {
            return new FileToolResult(
                    decision,
                    false,
                    null,
                    null,
                    "File checkpoint creation failed safely: " + error.getMessage());
        }
        try {
            switch (request.operation()) {
                case WRITE -> write(source, request.content());
                case CREATE_DIRECTORY -> SecureFileOperations.createDirectory(source);
                case MOVE -> SecureFileOperations.move(source, destination);
                case DELETE -> FileCheckpointStore.deleteTree(source);
                case READ -> throw new IllegalStateException("READ was already handled");
            }
            return new FileToolResult(
                    decision,
                    true,
                    null,
                    checkpointId,
                    "File operation completed with a restorable checkpoint");
        } catch (RuntimeException error) {
            try {
                checkpoints.restore(checkpointId);
            } catch (RuntimeException restoreError) {
                error.addSuppressed(restoreError);
            }
            return new FileToolResult(
                    decision,
                    false,
                    null,
                    checkpointId,
                    "File operation failed and rollback was attempted: " + error.getClass().getSimpleName());
        }
    }

    private static FileToolResult read(Path source, ToolPermissionDecision decision) {
        try {
            byte[] content = SecureFileOperations.readAllBytes(source, MAX_CONTENT_BYTES);
            return new FileToolResult(decision, true, content, null, "File read completed");
        } catch (RuntimeException error) {
            return new FileToolResult(
                    decision,
                    false,
                    null,
                    null,
                    "File read failed: " + error.getClass().getSimpleName());
        }
    }

    private static void write(Path source, byte[] content) {
        if (content.length > MAX_CONTENT_BYTES) {
            throw new FileCheckpointException(
                    "file content exceeds the structured write limit of " + MAX_CONTENT_BYTES + " bytes");
        }
        if (Files.exists(source, LinkOption.NOFOLLOW_LINKS) && Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
            throw new FileCheckpointException("cannot replace a directory with file content: " + source);
        }
        SecureFileOperations.writeFile(source, content);
    }

    private static FileToolResult denied(String reasonCode, String detail) {
        return new FileToolResult(
                ToolPermissionDecision.deny(reasonCode, detail),
                false,
                null,
                null,
                detail);
    }

    private static void rejectOverlappingMove(
            FileToolRequest request,
            Path source,
            Path destination) {
        if (request.operation() != FileOperation.MOVE) {
            return;
        }
        if (source.startsWith(destination) || destination.startsWith(source)) {
            throw new UnsafeFileRequestException(
                    "file.overlapping-move",
                    "Move source and destination must not be equal or contain one another");
        }
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
