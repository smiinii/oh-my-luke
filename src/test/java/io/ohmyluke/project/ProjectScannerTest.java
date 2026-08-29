package io.ohmyluke.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectScannerTest {
    private final ProjectScanner scanner = new ProjectScanner();

    @TempDir
    Path temporaryDirectory;

    @Test
    void detectsGradleJavaProjectAndReturnsSortedMetadata() throws IOException {
        Path project = Files.createDirectory(temporaryDirectory.resolve("gradle-project"));
        write(project, "src/test/java/ExampleTest.java", "class ExampleTest {}");
        write(project, "README.md", "# Example");
        write(project, "gradlew", "wrapper");
        write(project, "src/main/java/Example.java", "class Example {}");
        write(project, "settings.gradle.kts", "rootProject.name = \"example\"");
        write(project, "build.gradle.kts", "plugins { java }");

        ProjectProfile profile = scanner.scan(project);

        assertEquals(List.of(ProjectBuildSystem.GRADLE), profile.buildSystems());
        assertEquals(List.of(ProjectLanguage.JAVA), profile.languages());
        assertEquals(List.of(Path.of("src/main/java")), profile.sourceRoots());
        assertEquals(List.of(Path.of("src/test/java")), profile.testRoots());
        assertEquals(List.of(Path.of("README.md")), profile.importantDocuments());
        assertEquals(
                List.of("README.md", "build.gradle.kts", "gradlew", "settings.gradle.kts",
                        "src/main/java/Example.java", "src/test/java/ExampleTest.java"),
                relativeFiles(profile));
        assertEquals(
                List.of(List.of("./gradlew", "build"), List.of("./gradlew", "test")),
                profile.commandCandidates().stream().map(ProjectCommand::arguments).toList());
        assertFalse(profile.summary().truncated());
        assertEquals(project.toRealPath(), profile.projectRoot());
    }

    @Test
    void detectsMavenProjectAndWrapperCommands() throws IOException {
        Path project = Files.createDirectory(temporaryDirectory.resolve("maven-project"));
        write(project, "pom.xml", "<project />");
        write(project, "mvnw", "wrapper");
        write(project, "src/main/java/App.java", "class App {}");

        ProjectProfile profile = scanner.scan(project);

        assertEquals(List.of(ProjectBuildSystem.MAVEN), profile.buildSystems());
        assertEquals(
                List.of(List.of("./mvnw", "package"), List.of("./mvnw", "test")),
                profile.commandCandidates().stream().map(ProjectCommand::arguments).toList());
    }

    @Test
    void readsOnlyNpmBuildAndTestScriptNames() throws IOException {
        Path project = Files.createDirectory(temporaryDirectory.resolve("npm-project"));
        write(project, "package.json", """
                {
                  "scripts": {
                    "build": "secret implementation is not returned",
                    "test": "vitest",
                    "prepare": "must not become a candidate"
                  }
                }
                """);
        write(project, "src/index.ts", "export const value = 1;");
        write(project, "tests/index.test.js", "test('value', () => {});");

        ProjectProfile profile = scanner.scan(project);

        assertEquals(List.of(ProjectBuildSystem.NPM), profile.buildSystems());
        assertEquals(List.of(ProjectLanguage.JAVASCRIPT, ProjectLanguage.TYPESCRIPT), profile.languages());
        assertEquals(List.of(Path.of("src")), profile.sourceRoots());
        assertEquals(List.of(Path.of("tests")), profile.testRoots());
        assertEquals(
                List.of(List.of("npm", "run", "build"), List.of("npm", "test")),
                profile.commandCandidates().stream().map(ProjectCommand::arguments).toList());
        assertTrue(profile.commandCandidates().stream()
                .noneMatch(command -> command.arguments().contains("secret implementation is not returned")));
    }

    @Test
    void excludesInternalGeneratedSensitiveBinaryAndLargeEntries() throws IOException {
        Path project = Files.createDirectory(temporaryDirectory.resolve("safe-project"));
        write(project, ".git/config", "git secret");
        write(project, ".oml/state.json", "internal state");
        write(project, ".env", "TOKEN=secret");
        write(project, ".npmrc", "token=secret");
        write(project, ".config/gh/hosts.yml", "oauth_token: secret");
        write(project, "node_modules/pkg/index.js", "generated");
        write(project, "build/output.txt", "generated");
        write(project, "target/output.txt", "generated");
        write(project, "image.png", "binary");
        write(project, "large.txt", "123456789012345678901234567890123");
        write(project, ".env.example", "TOKEN=replace-me");
        write(project, "src/Main.java", "class Main {}");

        ProjectProfile profile = scanner.scan(project, new ProjectScanLimits(100, 100, 32, 10));

        assertEquals(List.of(".env.example", "src/Main.java"), relativeFiles(profile));
        assertTrue(profile.summary().notices().contains(ProjectScanNotice.GENERATED_PATH_SKIPPED));
        assertTrue(profile.summary().notices().contains(ProjectScanNotice.SENSITIVE_PATH_SKIPPED));
        assertTrue(profile.summary().notices().contains(ProjectScanNotice.BINARY_FILE_SKIPPED));
        assertTrue(profile.summary().notices().contains(ProjectScanNotice.LARGE_FILE_SKIPPED));
    }

    @Test
    void doesNotFollowSymbolicLinksOutsideProject() throws IOException {
        Path project = Files.createDirectory(temporaryDirectory.resolve("linked-project"));
        Path outside = write(temporaryDirectory, "outside/secret.txt", "outside");
        try {
            Files.createSymbolicLink(project.resolve("linked-secret.txt"), outside);
        } catch (UnsupportedOperationException | IOException | SecurityException error) {
            Assumptions.abort("Symbolic links are unavailable on this test platform");
        }

        ProjectProfile profile = scanner.scan(project);

        assertTrue(profile.files().isEmpty());
        assertTrue(profile.summary().notices().contains(ProjectScanNotice.SYMBOLIC_LINK_SKIPPED));
    }

    @Test
    void producesTheSameProfileRegardlessOfCreationOrder() throws IOException {
        Path first = Files.createDirectory(temporaryDirectory.resolve("same-name-one"));
        Path second = Files.createDirectory(temporaryDirectory.resolve("same-name-two"));
        write(first, "z.txt", "z");
        write(first, "a.txt", "a");
        write(first, "src/B.java", "class B {}");
        write(second, "src/B.java", "class B {}");
        write(second, "a.txt", "a");
        write(second, "z.txt", "z");

        ProjectProfile firstProfile = scanner.scan(first);
        ProjectProfile repeatedProfile = scanner.scan(first);
        ProjectProfile secondProfile = scanner.scan(second);

        assertEquals(firstProfile, repeatedProfile);
        assertEquals(relativeFiles(firstProfile), relativeFiles(secondProfile));
        assertEquals(firstProfile.languages(), secondProfile.languages());
        assertEquals(firstProfile.summary(), secondProfile.summary());
    }

    @Test
    void stopsDeterministicallyAtEntryLimit() throws IOException {
        Path project = Files.createDirectory(temporaryDirectory.resolve("entry-limit"));
        for (int index = 99; index >= 0; index--) {
            write(project, "%03d.txt".formatted(index), "entry");
        }

        ProjectProfile profile = scanner.scan(project, new ProjectScanLimits(2, 100, 100, 10));

        assertTrue(profile.files().isEmpty());
        assertEquals(0, profile.summary().visitedEntries());
        assertTrue(profile.summary().truncated());
        assertTrue(profile.summary().notices().contains(ProjectScanNotice.ENTRY_LIMIT_REACHED));
    }

    @Test
    void stopsAtTotalByteLimitAndSkipsBeyondDepthLimit() throws IOException {
        Path bytesProject = Files.createDirectory(temporaryDirectory.resolve("byte-limit"));
        write(bytesProject, "a.txt", "1234");
        write(bytesProject, "b.txt", "5678");
        ProjectProfile bytesProfile = scanner.scan(bytesProject, new ProjectScanLimits(10, 5, 5, 10));

        assertEquals(List.of("a.txt"), relativeFiles(bytesProfile));
        assertTrue(bytesProfile.summary().notices().contains(ProjectScanNotice.TOTAL_BYTES_LIMIT_REACHED));

        Path depthProject = Files.createDirectory(temporaryDirectory.resolve("depth-limit"));
        write(depthProject, "nested/file.txt", "hidden by depth limit");
        ProjectProfile depthProfile = scanner.scan(depthProject, new ProjectScanLimits(10, 100, 100, 0));

        assertTrue(depthProfile.files().isEmpty());
        assertTrue(depthProfile.summary().truncated());
        assertTrue(depthProfile.summary().notices().contains(ProjectScanNotice.DEPTH_LIMIT_REACHED));
    }

    @Test
    void reportsMalformedPackageManifestWithoutInventingCommands() throws IOException {
        Path project = Files.createDirectory(temporaryDirectory.resolve("bad-package"));
        write(project, "package.json", "{not-json");

        ProjectProfile profile = scanner.scan(project);

        assertEquals(List.of(ProjectBuildSystem.NPM), profile.buildSystems());
        assertTrue(profile.commandCandidates().isEmpty());
        assertTrue(profile.summary().notices().contains(ProjectScanNotice.MANIFEST_PARSE_FAILED));
    }

    @Test
    void reportsEmptyOrNonObjectPackageManifest() throws IOException {
        Path emptyProject = Files.createDirectory(temporaryDirectory.resolve("empty-package"));
        write(emptyProject, "package.json", "");
        Path arrayProject = Files.createDirectory(temporaryDirectory.resolve("array-package"));
        write(arrayProject, "package.json", "[]");

        assertTrue(scanner.scan(emptyProject).summary().notices()
                .contains(ProjectScanNotice.MANIFEST_PARSE_FAILED));
        assertTrue(scanner.scan(arrayProject).summary().notices()
                .contains(ProjectScanNotice.MANIFEST_PARSE_FAILED));
    }

    @Test
    void ignoresNestedBuildMarkersWhenCreatingRootCommands() throws IOException {
        Path project = Files.createDirectory(temporaryDirectory.resolve("nested-manifests"));
        write(project, "docs/package.json", "{\"scripts\":{\"test\":\"docs-test\"}}");
        write(project, "examples/pom.xml", "<project />");
        write(project, "samples/build.gradle.kts", "plugins { java }");

        ProjectProfile profile = scanner.scan(project);

        assertTrue(profile.buildSystems().isEmpty());
        assertTrue(profile.commandCandidates().isEmpty());
    }

    @Test
    void rejectsInternalSensitiveOrSymbolicLinkRoots() throws IOException {
        Path project = Files.createDirectory(temporaryDirectory.resolve("forbidden-roots"));
        Path oml = Files.createDirectories(project.resolve(".oml/runs"));
        Path git = Files.createDirectories(project.resolve(".git/objects"));
        Path ssh = Files.createDirectories(project.resolve(".ssh"));
        write(oml, "state.json", "state");

        assertThrows(IllegalArgumentException.class, () -> scanner.scan(oml));
        assertThrows(IllegalArgumentException.class, () -> scanner.scan(git));
        assertThrows(IllegalArgumentException.class, () -> scanner.scan(ssh));

        Path link = temporaryDirectory.resolve("root-link");
        try {
            Files.createSymbolicLink(link, project);
        } catch (UnsupportedOperationException | IOException | SecurityException error) {
            Assumptions.abort("Symbolic links are unavailable on this test platform");
        }
        assertThrows(IllegalArgumentException.class, () -> scanner.scan(link));
    }

    @Test
    void publicModelsRejectPathsThatCanEscapeProject() {
        assertThrows(IllegalArgumentException.class, () -> new ProjectFile(
                Path.of("../secret.txt"), 1, ProjectFileKind.OTHER, Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new ProjectFile(
                Path.of(""), 1, ProjectFileKind.OTHER, Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new ProjectProfile(
                temporaryDirectory.toAbsolutePath().normalize(),
                "project",
                List.of(),
                List.of(),
                List.of(Path.of("../outside")),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                new ProjectScanSummary(0, 0, 0, 0, false, List.of())));
    }

    @Test
    void rejectsMissingOrNonDirectoryRootsAndInvalidLimits() throws IOException {
        Path regularFile = write(temporaryDirectory, "regular.txt", "text");

        assertThrows(IllegalArgumentException.class, () -> scanner.scan(temporaryDirectory.resolve("missing")));
        assertThrows(IllegalArgumentException.class, () -> scanner.scan(regularFile));
        assertThrows(IllegalArgumentException.class, () -> new ProjectScanLimits(0, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new ProjectScanLimits(1, 1, 2, 1));
        assertThrows(IllegalArgumentException.class, () -> new ProjectScanLimits(1, 1, 1, 257));
    }

    private static List<String> relativeFiles(ProjectProfile profile) {
        return profile.files().stream()
                .map(file -> file.relativePath().toString().replace('\\', '/'))
                .toList();
    }

    private static Path write(Path root, String relativePath, String content) throws IOException {
        Path target = root.resolve(relativePath);
        Files.createDirectories(target.getParent());
        return Files.writeString(target, content);
    }
}
