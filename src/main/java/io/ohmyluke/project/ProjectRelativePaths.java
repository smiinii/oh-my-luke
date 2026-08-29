package io.ohmyluke.project;

import java.nio.file.Path;
import java.util.Objects;

final class ProjectRelativePaths {
    private ProjectRelativePaths() {}

    static Path requireSafe(Path path, String name) {
        Objects.requireNonNull(path, name);
        if (path.toString().isBlank()
                || path.isAbsolute()
                || !path.normalize().equals(path)
                || path.getName(0).toString().equals("..")) {
            throw new IllegalArgumentException(name + " must stay inside the project");
        }
        return path;
    }
}
