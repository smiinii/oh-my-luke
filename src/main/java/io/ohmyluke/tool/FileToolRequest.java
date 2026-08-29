package io.ohmyluke.tool;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;

/** A file action with explicit paths and bytes, never a shell command. */
public record FileToolRequest(
        String operationId,
        FileOperation operation,
        Path path,
        Path destination,
        byte[] content) {
    public FileToolRequest {
        operationId = requireText(operationId, "operationId");
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(path, "path");
        content = content == null ? new byte[0] : Arrays.copyOf(content, content.length);
        if (operation == FileOperation.MOVE && destination == null) {
            throw new IllegalArgumentException("MOVE requires a destination");
        }
        if (operation != FileOperation.MOVE && destination != null) {
            throw new IllegalArgumentException(operation + " must not have a destination");
        }
        if (operation != FileOperation.WRITE && content.length > 0) {
            throw new IllegalArgumentException(operation + " must not have content");
        }
    }

    @Override
    public byte[] content() {
        return Arrays.copyOf(content, content.length);
    }

    public static FileToolRequest read(String operationId, Path path) {
        return new FileToolRequest(operationId, FileOperation.READ, path, null, null);
    }

    public static FileToolRequest write(String operationId, Path path, byte[] content) {
        return new FileToolRequest(operationId, FileOperation.WRITE, path, null, content);
    }

    public static FileToolRequest createDirectory(String operationId, Path path) {
        return new FileToolRequest(operationId, FileOperation.CREATE_DIRECTORY, path, null, null);
    }

    public static FileToolRequest move(String operationId, Path source, Path destination) {
        return new FileToolRequest(operationId, FileOperation.MOVE, source, destination, null);
    }

    public static FileToolRequest delete(String operationId, Path path) {
        return new FileToolRequest(operationId, FileOperation.DELETE, path, null, null);
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(name + " must be non-blank and contain no NUL");
        }
        return value;
    }
}
