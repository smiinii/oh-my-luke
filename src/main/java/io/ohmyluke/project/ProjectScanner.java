package io.ohmyluke.project;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.ohmyluke.policy.SensitivePathPolicy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/** Builds a bounded, deterministic metadata profile without executing project code. */
public final class ProjectScanner {
    private static final Set<String> EXCLUDED_DIRECTORIES = Set.of(
            ".git",
            ".oml",
            "node_modules",
            ".gradle",
            "build",
            "target",
            "dist",
            "out",
            "coverage",
            ".next",
            ".cache",
            "__pycache__");
    private static final Set<String> BINARY_SUFFIXES = Set.of(
            ".class", ".jar", ".zip", ".gz", ".tar", ".7z",
            ".png", ".jpg", ".jpeg", ".gif", ".webp", ".ico",
            ".pdf", ".exe", ".dll", ".so", ".dylib", ".o", ".a",
            ".wasm", ".bin", ".mp3", ".mp4", ".mov");
    private static final Set<String> CONFIGURATION_SUFFIXES = Set.of(
            ".json", ".yaml", ".yml", ".toml", ".xml", ".properties", ".gradle");
    private static final ObjectMapper JSON = new ObjectMapper();

    public ProjectProfile scan(Path projectRoot) {
        return scan(projectRoot, ProjectScanLimits.defaults());
    }

    public ProjectProfile scan(Path projectRoot, ProjectScanLimits limits) {
        Objects.requireNonNull(projectRoot, "projectRoot");
        Objects.requireNonNull(limits, "limits");
        Path configuredRoot = projectRoot.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(configuredRoot)) {
            throw new IllegalArgumentException("projectRoot must not be a symbolic link");
        }
        if (isForbiddenRoot(configuredRoot)) {
            throw new IllegalArgumentException("projectRoot must not expose internal or sensitive data");
        }
        Path realRoot;
        try {
            realRoot = configuredRoot.toRealPath();
        } catch (IOException error) {
            throw new IllegalArgumentException("projectRoot must exist and be resolvable", error);
        }
        if (!Files.isDirectory(realRoot, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("projectRoot must be a directory");
        }

        Accumulator accumulator = new Accumulator(realRoot, limits);
        scanDirectory(realRoot, 0, accumulator);
        return accumulator.profile();
    }

    private void scanDirectory(Path directory, int depth, Accumulator accumulator) {
        if (accumulator.stopped) {
            return;
        }
        if (depth > accumulator.limits.maxDepth()) {
            accumulator.exclude(ProjectScanNotice.DEPTH_LIMIT_REACHED);
            accumulator.truncated = true;
            return;
        }

        List<Path> entries = new ArrayList<>();
        int remainingEntries = accumulator.limits.maxEntries() - accumulator.visitedEntries;
        try (Stream<Path> listed = Files.list(directory)) {
            Iterator<Path> iterator = listed.iterator();
            while (iterator.hasNext()) {
                if (entries.size() >= remainingEntries) {
                    accumulator.stop(ProjectScanNotice.ENTRY_LIMIT_REACHED);
                    return;
                }
                entries.add(iterator.next());
            }
        } catch (IOException error) {
            throw new ProjectScanException("Unable to list project directory: " + directory, error);
        }
        entries.sort(Comparator.comparing(accumulator::relativeText));

        for (Path entry : entries) {
            if (accumulator.stopped) {
                return;
            }
            accumulator.visitedEntries++;
            inspectEntry(entry, depth, accumulator);
        }
    }

    private void inspectEntry(Path entry, int parentDepth, Accumulator accumulator) {
        BasicFileAttributes attributes;
        try {
            attributes = Files.readAttributes(
                    entry, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException error) {
            throw new ProjectScanException("Unable to inspect project entry: " + entry, error);
        }

        Path relative = accumulator.relative(entry);
        if (attributes.isSymbolicLink()) {
            accumulator.exclude(ProjectScanNotice.SYMBOLIC_LINK_SKIPPED);
            return;
        }
        if (SensitivePathPolicy.isSensitive(relative)) {
            accumulator.exclude(ProjectScanNotice.SENSITIVE_PATH_SKIPPED);
            return;
        }
        if (attributes.isDirectory()) {
            if (isExcludedDirectory(relative)) {
                accumulator.exclude(ProjectScanNotice.GENERATED_PATH_SKIPPED);
                return;
            }
            if (!isCanonicalChildDirectory(entry, accumulator.root)) {
                accumulator.exclude(ProjectScanNotice.SYMBOLIC_LINK_SKIPPED);
                return;
            }
            accumulator.directories.add(relative);
            scanDirectory(entry, parentDepth + 1, accumulator);
            return;
        }
        if (!attributes.isRegularFile()) {
            accumulator.exclude(ProjectScanNotice.UNSUPPORTED_FILE_SKIPPED);
            return;
        }
        if (isBinary(relative)) {
            accumulator.exclude(ProjectScanNotice.BINARY_FILE_SKIPPED);
            return;
        }
        if (attributes.size() > accumulator.limits.maxFileBytes()) {
            accumulator.exclude(ProjectScanNotice.LARGE_FILE_SKIPPED);
            return;
        }
        if (attributes.size() > accumulator.limits.maxIncludedBytes() - accumulator.includedBytes) {
            accumulator.stop(ProjectScanNotice.TOTAL_BYTES_LIMIT_REACHED);
            return;
        }

        ProjectFile file = classify(relative, attributes.size());
        accumulator.files.add(file);
        accumulator.includedBytes += attributes.size();
        accumulator.detect(relative, file.language());
        if (relative.getNameCount() == 1
                && relative.getFileName().toString().equalsIgnoreCase("package.json")) {
            accumulator.readNpmScripts(entry);
        }
    }

    private static boolean isForbiddenRoot(Path root) {
        if (SensitivePathPolicy.isSensitive(root)) {
            return true;
        }
        for (Path part : root) {
            String name = part.toString();
            if (name.equalsIgnoreCase(".git") || name.equalsIgnoreCase(".oml")) {
                return true;
            }
        }
        return false;
    }

    private static boolean isCanonicalChildDirectory(Path directory, Path root) {
        try {
            Path real = directory.toRealPath();
            return real.startsWith(root)
                    && real.equals(directory.toAbsolutePath().normalize());
        } catch (IOException error) {
            return false;
        }
    }

    private static boolean isExcludedDirectory(Path relative) {
        String name = relative.getFileName().toString().toLowerCase(Locale.ROOT);
        return EXCLUDED_DIRECTORIES.contains(name);
    }

    private static boolean isBinary(Path relative) {
        String name = relative.getFileName().toString().toLowerCase(Locale.ROOT);
        return BINARY_SUFFIXES.stream().anyMatch(name::endsWith);
    }

    private static ProjectFile classify(Path relative, long size) {
        Optional<ProjectLanguage> language = languageOf(relative);
        String name = relative.getFileName().toString().toLowerCase(Locale.ROOT);
        ProjectFileKind kind;
        if (isDocumentation(name)) {
            kind = ProjectFileKind.DOCUMENTATION;
        } else if (isTestPath(relative)) {
            kind = ProjectFileKind.TEST;
        } else if (language.isPresent() && !isBuildMarker(name)) {
            kind = ProjectFileKind.SOURCE;
        } else if (isBuildMarker(name)
                || CONFIGURATION_SUFFIXES.stream().anyMatch(name::endsWith)
                || name.equals("gradlew")
                || name.equals("mvnw")) {
            kind = ProjectFileKind.CONFIGURATION;
        } else {
            kind = ProjectFileKind.OTHER;
        }
        if (isBuildMarker(name)) {
            language = Optional.empty();
        }
        return new ProjectFile(relative, size, kind, language);
    }

    private static Optional<ProjectLanguage> languageOf(Path relative) {
        String name = relative.getFileName().toString().toLowerCase(Locale.ROOT);
        if (isBuildMarker(name)) {
            return Optional.empty();
        }
        if (name.endsWith(".java")) {
            return Optional.of(ProjectLanguage.JAVA);
        }
        if (name.endsWith(".kt") || name.endsWith(".kts")) {
            return Optional.of(ProjectLanguage.KOTLIN);
        }
        if (name.endsWith(".ts") || name.endsWith(".tsx")) {
            return Optional.of(ProjectLanguage.TYPESCRIPT);
        }
        if (name.endsWith(".js") || name.endsWith(".jsx") || name.endsWith(".mjs") || name.endsWith(".cjs")) {
            return Optional.of(ProjectLanguage.JAVASCRIPT);
        }
        if (name.endsWith(".py")) {
            return Optional.of(ProjectLanguage.PYTHON);
        }
        if (name.endsWith(".go")) {
            return Optional.of(ProjectLanguage.GO);
        }
        if (name.endsWith(".rs")) {
            return Optional.of(ProjectLanguage.RUST);
        }
        return Optional.empty();
    }

    private static boolean isTestPath(Path relative) {
        String normalized = relative.toString().replace('\\', '/').toLowerCase(Locale.ROOT);
        return normalized.startsWith("src/test/")
                || normalized.startsWith("test/")
                || normalized.startsWith("tests/")
                || normalized.contains("/__tests__/")
                || normalized.startsWith("__tests__/");
    }

    private static boolean isDocumentation(String name) {
        return name.startsWith("readme")
                || name.startsWith("contributing")
                || name.startsWith("changelog")
                || name.equals("agents.md")
                || name.startsWith("license");
    }

    private static boolean isBuildMarker(String name) {
        return name.equals("build.gradle")
                || name.equals("build.gradle.kts")
                || name.equals("settings.gradle")
                || name.equals("settings.gradle.kts")
                || name.equals("pom.xml")
                || name.equals("package.json");
    }

    private static final class Accumulator {
        private final Path root;
        private final ProjectScanLimits limits;
        private final List<Path> directories = new ArrayList<>();
        private final List<ProjectFile> files = new ArrayList<>();
        private final EnumSet<ProjectBuildSystem> buildSystems = EnumSet.noneOf(ProjectBuildSystem.class);
        private final EnumSet<ProjectLanguage> languages = EnumSet.noneOf(ProjectLanguage.class);
        private final EnumSet<ProjectScanNotice> notices = EnumSet.noneOf(ProjectScanNotice.class);
        private boolean npmBuild;
        private boolean npmTest;
        private int visitedEntries;
        private int excludedEntries;
        private long includedBytes;
        private boolean truncated;
        private boolean stopped;

        private Accumulator(Path root, ProjectScanLimits limits) {
            this.root = root;
            this.limits = limits;
        }

        private Path relative(Path path) {
            return root.relativize(path.toAbsolutePath().normalize()).normalize();
        }

        private String relativeText(Path path) {
            return relative(path).toString().replace('\\', '/');
        }

        private void exclude(ProjectScanNotice notice) {
            excludedEntries++;
            notices.add(notice);
        }

        private void stop(ProjectScanNotice notice) {
            notices.add(notice);
            truncated = true;
            stopped = true;
        }

        private void detect(Path relative, Optional<ProjectLanguage> language) {
            String name = relative.getFileName().toString().toLowerCase(Locale.ROOT);
            if (relative.getNameCount() == 1 && (name.equals("build.gradle")
                    || name.equals("build.gradle.kts")
                    || name.equals("settings.gradle")
                    || name.equals("settings.gradle.kts"))) {
                buildSystems.add(ProjectBuildSystem.GRADLE);
            } else if (relative.getNameCount() == 1 && name.equals("pom.xml")) {
                buildSystems.add(ProjectBuildSystem.MAVEN);
            } else if (relative.getNameCount() == 1 && name.equals("package.json")) {
                buildSystems.add(ProjectBuildSystem.NPM);
            }
            language.ifPresent(languages::add);
        }

        private void readNpmScripts(Path packageJson) {
            try {
                JsonNode manifest = JSON.readTree(Files.readAllBytes(packageJson));
                if (manifest == null || !manifest.isObject()) {
                    notices.add(ProjectScanNotice.MANIFEST_PARSE_FAILED);
                    return;
                }
                JsonNode scripts = manifest.path("scripts");
                npmBuild |= scripts.isObject() && scripts.has("build") && scripts.get("build").isTextual();
                npmTest |= scripts.isObject() && scripts.has("test") && scripts.get("test").isTextual();
            } catch (IOException error) {
                notices.add(ProjectScanNotice.MANIFEST_PARSE_FAILED);
            }
        }

        private ProjectProfile profile() {
            files.sort(Comparator.comparing(file -> file.relativePath().toString().replace('\\', '/')));
            directories.sort(Comparator.comparing(path -> path.toString().replace('\\', '/')));
            List<Path> sourceRoots = sourceRoots();
            List<Path> testRoots = testRoots();
            List<Path> importantDocuments = files.stream()
                    .filter(file -> file.kind() == ProjectFileKind.DOCUMENTATION)
                    .map(ProjectFile::relativePath)
                    .toList();
            String projectName = root.getFileName() == null ? root.toString() : root.getFileName().toString();
            return new ProjectProfile(
                    root,
                    projectName,
                    List.copyOf(buildSystems),
                    List.copyOf(languages),
                    sourceRoots,
                    testRoots,
                    importantDocuments,
                    files,
                    commands(),
                    new ProjectScanSummary(
                            visitedEntries,
                            files.size(),
                            excludedEntries,
                            includedBytes,
                            truncated,
                            List.copyOf(notices)));
        }

        private List<Path> sourceRoots() {
            List<Path> roots = new ArrayList<>();
            addIfDirectory(roots, "src/main/java");
            addIfDirectory(roots, "src/main/kotlin");
            if (roots.isEmpty()) {
                addIfDirectory(roots, "src");
            }
            addIfDirectory(roots, "lib");
            addIfDirectory(roots, "app");
            return List.copyOf(roots);
        }

        private List<Path> testRoots() {
            List<Path> roots = new ArrayList<>();
            addIfDirectory(roots, "src/test/java");
            addIfDirectory(roots, "src/test/kotlin");
            addIfDirectory(roots, "test");
            addIfDirectory(roots, "tests");
            addIfDirectory(roots, "__tests__");
            return List.copyOf(roots);
        }

        private void addIfDirectory(List<Path> roots, String candidate) {
            Path path = Path.of(candidate);
            if (directories.contains(path)) {
                roots.add(path);
            }
        }

        private List<ProjectCommand> commands() {
            List<ProjectCommand> commands = new ArrayList<>();
            if (buildSystems.contains(ProjectBuildSystem.GRADLE)) {
                String executable = containsFile("gradlew") ? "./gradlew" : "gradle";
                commands.add(command(ProjectCommand.Purpose.BUILD, ProjectBuildSystem.GRADLE,
                        List.of(executable, "build"), "Gradle build marker detected"));
                commands.add(command(ProjectCommand.Purpose.TEST, ProjectBuildSystem.GRADLE,
                        List.of(executable, "test"), "Gradle build marker detected"));
            }
            if (buildSystems.contains(ProjectBuildSystem.MAVEN)) {
                String executable = containsFile("mvnw") ? "./mvnw" : "mvn";
                commands.add(command(ProjectCommand.Purpose.BUILD, ProjectBuildSystem.MAVEN,
                        List.of(executable, "package"), "Maven pom.xml detected"));
                commands.add(command(ProjectCommand.Purpose.TEST, ProjectBuildSystem.MAVEN,
                        List.of(executable, "test"), "Maven pom.xml detected"));
            }
            if (buildSystems.contains(ProjectBuildSystem.NPM)) {
                if (npmBuild) {
                    commands.add(command(ProjectCommand.Purpose.BUILD, ProjectBuildSystem.NPM,
                            List.of("npm", "run", "build"), "package.json build script detected"));
                }
                if (npmTest) {
                    commands.add(command(ProjectCommand.Purpose.TEST, ProjectBuildSystem.NPM,
                            List.of("npm", "test"), "package.json test script detected"));
                }
            }
            return List.copyOf(commands);
        }

        private boolean containsFile(String relativePath) {
            Path expected = Path.of(relativePath);
            return files.stream().anyMatch(file -> file.relativePath().equals(expected));
        }

        private static ProjectCommand command(
                ProjectCommand.Purpose purpose,
                ProjectBuildSystem buildSystem,
                List<String> arguments,
                String reason) {
            return new ProjectCommand(purpose, buildSystem, arguments, reason);
        }
    }
}
