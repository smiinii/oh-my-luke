package io.ohmyluke.project;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

public record ProjectFile(
        Path relativePath,
        long size,
        ProjectFileKind kind,
        Optional<ProjectLanguage> language) {
    public ProjectFile {
        Objects.requireNonNull(relativePath, "relativePath");
        if (relativePath.isAbsolute() || !relativePath.normalize().equals(relativePath)) {
            throw new IllegalArgumentException("relativePath must be normalized and relative");
        }
        if (size < 0) {
            throw new IllegalArgumentException("size must not be negative");
        }
        Objects.requireNonNull(kind, "kind");
        language = Objects.requireNonNull(language, "language");
    }
}
