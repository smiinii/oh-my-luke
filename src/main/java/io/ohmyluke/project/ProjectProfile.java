package io.ohmyluke.project;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

public record ProjectProfile(
        Path projectRoot,
        String projectName,
        List<ProjectBuildSystem> buildSystems,
        List<ProjectLanguage> languages,
        List<Path> sourceRoots,
        List<Path> testRoots,
        List<Path> importantDocuments,
        List<ProjectFile> files,
        List<ProjectCommand> commandCandidates,
        ProjectScanSummary summary) {
    public ProjectProfile {
        Objects.requireNonNull(projectRoot, "projectRoot");
        if (!projectRoot.isAbsolute() || !projectRoot.normalize().equals(projectRoot)) {
            throw new IllegalArgumentException("projectRoot must be normalized and absolute");
        }
        projectName = Objects.requireNonNull(projectName, "projectName");
        if (projectName.isBlank()) {
            throw new IllegalArgumentException("projectName must not be blank");
        }
        buildSystems = List.copyOf(Objects.requireNonNull(buildSystems, "buildSystems"));
        languages = List.copyOf(Objects.requireNonNull(languages, "languages"));
        sourceRoots = relativePaths(sourceRoots, "sourceRoots");
        testRoots = relativePaths(testRoots, "testRoots");
        importantDocuments = relativePaths(importantDocuments, "importantDocuments");
        files = List.copyOf(Objects.requireNonNull(files, "files"));
        commandCandidates = List.copyOf(Objects.requireNonNull(commandCandidates, "commandCandidates"));
        Objects.requireNonNull(summary, "summary");
    }

    private static List<Path> relativePaths(List<Path> paths, String name) {
        List<Path> copy = List.copyOf(Objects.requireNonNull(paths, name));
        copy.forEach(path -> ProjectRelativePaths.requireSafe(path, name));
        return copy;
    }
}
