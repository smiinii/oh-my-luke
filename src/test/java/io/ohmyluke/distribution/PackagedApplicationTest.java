package io.ohmyluke.distribution;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Verifies the downloadable archive, bundled runtime, launcher, and size budget together. */
class PackagedApplicationTest {
    private static final long MAX_ARCHIVE_BYTES = 50_000_000;
    private static final long MAX_IMAGE_BYTES = 150_000_000;
    @TempDir Path directory;

    @Test void archiveRunsWithoutAnExternalJavaAndStaysInsideTheInitialSizeBudget() throws Exception {
        String os = property("omluke.package.os");
        String arch = property("omluke.package.arch");
        Path archive = Path.of(property("omluke.package.archive"));
        Path checksum = Path.of(property("omluke.package.checksum"));
        Path sourceImage = Path.of(property("omluke.package.sourceImage"));
        String imageName = property("omluke.package.imageName");
        String releaseRootName = property("omluke.package.releaseRootName");
        String productVersion = property("omluke.package.productVersion");
        assertTrue(productVersion.matches("[0-9]+\\.[0-9]+\\.[0-9]+-rc\\.[0-9]+"),
                "trial release version must use MAJOR.MINOR.PATCH-rc.NUMBER");
        assertEquals("v" + productVersion, property("omluke.package.releaseTag"),
                "release tag and product version must match exactly");
        assertTrue(Files.isRegularFile(archive));

        String archiveSha256 = sha256(archive);
        Files.writeString(checksum, archiveSha256 + "  " + archive.getFileName() + System.lineSeparator());
        assertChecksum(checksum, archive, archiveSha256);
        assertSafeArchiveEntries(archive, releaseRootName);

        Path extracted = Files.createDirectory(directory.resolve("extracted"));
        Result extraction = run(List.of("/usr/bin/tar", "-xzf", archive.toString(), "-C", extracted.toString()), directory, false);
        assertEquals(0, extraction.exitCode(), extraction.output());
        Path releaseRoot = extracted.resolve(releaseRootName);
        Path image = releaseRoot.resolve(imageName);
        Path installer = releaseRoot.resolve("install.sh");
        Path uninstaller = releaseRoot.resolve("uninstall.sh");
        assertTrue(Files.isExecutable(installer), "the installer must preserve executable permissions");
        assertTrue(Files.isExecutable(uninstaller), "the uninstaller must preserve executable permissions");
        assertEquals(productVersion, versionFrom(releaseRoot.resolve("VERSION")));
        assertPlatform(releaseRoot.resolve("PLATFORM"), os, arch);
        assertTrue(Files.isRegularFile(releaseRoot.resolve("examples/start/task.json")),
                "the downloadable bundle must include a first-run example");
        assertSafeExtractedLinks(releaseRoot);
        Path launcher = os.equals("macos")
                ? image.resolve("Contents/MacOS/omluke")
                : image.resolve("bin/omluke");
        Path runtimeJava = os.equals("macos")
                ? image.resolve("Contents/runtime/Contents/Home/bin/java")
                : image.resolve("lib/runtime/bin/java");
        assertTrue(Files.isExecutable(launcher), "the extracted launcher must preserve executable permissions");
        assertTrue(Files.isExecutable(runtimeJava), "the app image must contain its own Java runtime");
        assertEquals(symbolicLinks(sourceImage), symbolicLinks(image),
                "the archive must preserve every runtime symbolic link and target");

        Path packageMetadata = os.equals("macos")
                ? image.resolve("Contents/app/.jpackage.xml")
                : image.resolve("lib/app/.jpackage.xml");
        String nativeVersion = tagValue(Files.readString(packageMetadata), "app-version");
        assertEquals(nativeVersionFor(productVersion), nativeVersion);
        if (os.equals("macos")) {
            verifyMacBundle(image, nativeVersion);
        }

        Path helpProject = Files.createDirectory(directory.resolve("help-project"));
        Result help = run(List.of(launcher.toString(), "--help"), helpProject, true);
        assertEquals(0, help.exitCode(), help.output());
        assertTrue(help.output().contains("Oh My Luke"), help.output());
        assertTrue(help.output().contains("사용법: omluke start"), help.output());

        Result version = run(List.of(launcher.toString(), "--version"), helpProject, true);
        assertEquals(0, version.exitCode(), version.output());
        assertEquals("omluke " + productVersion, version.output().strip());

        Path userHome = Files.createDirectory(directory.resolve("user-home")).toRealPath();
        Path codexState = userHome.resolve(".codex/auth.json");
        Files.createDirectories(codexState.getParent());
        Files.writeString(codexState, "user-owned-codex-state");
        Path prefix = directory.toRealPath().resolve("prefix");
        seedPreviousVersion(prefix, os);
        Path previousVersion = prefix.resolve("lib/omluke/versions/0.0.0-test/VERSION");
        Path previousLauncher = os.equals("macos")
                ? prefix.resolve("lib/omluke/versions/0.0.0-test/omluke.app/Contents/MacOS/omluke")
                : prefix.resolve("lib/omluke/versions/0.0.0-test/omluke/bin/omluke");
        String previousVersionHash = sha256(previousVersion);
        String previousLauncherHash = sha256(previousLauncher);
        Result installed = run(List.of("/bin/sh", installer.toString(), "--prefix", prefix.toString()),
                releaseRoot, false, Map.of("HOME", userHome.toString()));
        assertEquals(0, installed.exitCode(), installed.output());
        Path installedLauncher = prefix.resolve("bin/omluke");
        assertTrue(Files.isSymbolicLink(installedLauncher));
        Path installedImage = prefix.resolve("lib/omluke/versions").resolve(productVersion).resolve(imageName);
        Path expectedInstalledLauncher = os.equals("macos")
                ? installedImage.resolve("Contents/MacOS/omluke")
                : installedImage.resolve("bin/omluke");
        assertEquals(expectedInstalledLauncher, Files.readSymbolicLink(installedLauncher),
                "the command must point to the exact installed version launcher");
        assertEquals(symbolicLinks(image), symbolicLinks(installedImage),
                "installation must preserve every runtime symbolic link and target");
        if (os.equals("macos")) {
            verifyMacBundle(installedImage, nativeVersion);
        }
        assertTrue(Files.isDirectory(prefix.resolve("lib/omluke/versions/0.0.0-test")),
                "switching versions must leave the prior version available for rollback");
        assertEquals(previousVersionHash, sha256(previousVersion), "updating must preserve the prior VERSION file");
        assertEquals(previousLauncherHash, sha256(previousLauncher), "updating must preserve the prior launcher");
        assertTrue(Files.isExecutable(previousLauncher), "the prior launcher must remain executable for rollback");
        assertEquals(0, run(List.of(previousLauncher.toString()), helpProject, true).exitCode());

        Result installedVersion = run(List.of(installedLauncher.toString(), "--version"), helpProject, true,
                Map.of("HOME", userHome.toString()));
        assertEquals(0, installedVersion.exitCode(), installedVersion.output());
        assertEquals("omluke " + productVersion, installedVersion.output().strip());

        Path workflowProject = Files.createDirectory(directory.resolve("workflow-project"));
        Files.copy(releaseRoot.resolve("examples/workflows/check-and-approve.json"),
                workflowProject.resolve("workflow.json"));
        Files.copy(releaseRoot.resolve("examples/workflows/ready.txt"), workflowProject.resolve("hello.txt"));
        Result waiting = run(List.of(installedLauncher.toString(), "workflow", "workflow.json", "--run-id", "package-test"),
                workflowProject, true, Map.of("HOME", userHome.toString()));
        assertEquals(3, waiting.exitCode(), waiting.output());
        assertTrue(waiting.output().contains("result=WAITING_APPROVAL"), waiting.output());
        assertTrue(waiting.output().contains("aiAttempts=0"), waiting.output());
        assertTrue(waiting.output().contains("recordedUsage=0"), waiting.output());
        String requestId = lineValue(waiting.output(), "approvalRequestId=");

        Result approved = run(List.of(installedLauncher.toString(), "approve", "package-test", requestId),
                workflowProject, true, Map.of("HOME", userHome.toString()));
        assertEquals(0, approved.exitCode(), approved.output());
        Result completed = run(List.of(installedLauncher.toString(), "resume", "package-test"),
                workflowProject, true, Map.of("HOME", userHome.toString()));
        assertEquals(0, completed.exitCode(), completed.output());
        assertTrue(completed.output().contains("result=SUCCEEDED"), completed.output());
        assertTrue(completed.output().contains("aiAttempts=0"), completed.output());
        assertTrue(completed.output().contains("recordedUsage=0"), completed.output());
        Path runState = workflowProject.resolve(".oml/runs/package-test/state.json");
        assertTrue(Files.isRegularFile(runState));

        Result reinstalled = run(List.of("/bin/sh", installer.toString(), "--prefix", prefix.toString()),
                releaseRoot, false, Map.of("HOME", userHome.toString()));
        assertEquals(0, reinstalled.exitCode(), reinstalled.output());
        assertEquals("omluke " + productVersion,
                run(List.of(installedLauncher.toString(), "--version"), helpProject, true,
                        Map.of("HOME", userHome.toString())).output().strip());

        String projectFileBeforeUninstall = sha256(workflowProject.resolve("hello.txt"));
        String runStateBeforeUninstall = sha256(runState);
        String codexStateBeforeUninstall = sha256(codexState);
        Path installedUninstaller = prefix.resolve("lib/omluke/uninstall.sh");
        Result removed = run(List.of("/bin/sh", installedUninstaller.toString(), "--prefix", prefix.toString()),
                workflowProject, false, Map.of("HOME", userHome.toString()));
        assertEquals(0, removed.exitCode(), removed.output());
        assertFalse(Files.exists(prefix.resolve("lib/omluke"), LinkOption.NOFOLLOW_LINKS));
        assertFalse(Files.exists(installedLauncher, LinkOption.NOFOLLOW_LINKS));
        assertEquals(projectFileBeforeUninstall, sha256(workflowProject.resolve("hello.txt")));
        assertEquals(runStateBeforeUninstall, sha256(runState));
        assertEquals(codexStateBeforeUninstall, sha256(codexState));

        Result installedAgain = run(List.of("/bin/sh", installer.toString(), "--prefix", prefix.toString()),
                releaseRoot, false, Map.of("HOME", userHome.toString()));
        assertEquals(0, installedAgain.exitCode(), installedAgain.output());
        Result preservedRun = run(List.of(installedLauncher.toString(), "inspect", "package-test"),
                workflowProject, true, Map.of("HOME", userHome.toString()));
        assertEquals(0, preservedRun.exitCode(), preservedRun.output());
        assertTrue(preservedRun.output().contains("result=SUCCEEDED"), preservedRun.output());
        Result finalRemoval = run(List.of("/bin/sh", prefix.resolve("lib/omluke/uninstall.sh").toString(),
                        "--prefix", prefix.toString()), workflowProject, false, Map.of("HOME", userHome.toString()));
        assertEquals(0, finalRemoval.exitCode(), finalRemoval.output());

        assertInstallerRefusesForeignCommand(installer, userHome);
        assertUninstallerRefusesForeignDirectory(uninstaller, userHome);

        Result runtime = run(List.of(runtimeJava.toString(), "-XshowSettings:properties", "-version"),
                workflowProject, true);
        assertEquals(0, runtime.exitCode(), runtime.output());
        String javaVersion = lineValue(runtime.output(), "java.version = ");

        long archiveBytes = Files.size(archive);
        long imageBytes;
        try (var paths = Files.walk(image)) {
            imageBytes = paths.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .mapToLong(PackagedApplicationTest::size).sum();
        }
        assertTrue(archiveBytes <= MAX_ARCHIVE_BYTES,
                () -> "archive exceeds 50 MB: " + archiveBytes);
        assertTrue(imageBytes <= MAX_IMAGE_BYTES,
                () -> "app image exceeds 150 MB: " + imageBytes);
        String evidence = String.format(Locale.ROOT,
                "{\"os\":\"%s\",\"arch\":\"%s\",\"productVersion\":\"%s\","
                        + "\"nativeVersion\":\"%s\",\"javaVersion\":\"%s\","
                        + "\"archiveBytes\":%d,\"imageBytes\":%d,\"sha256\":\"%s\","
                        + "\"ranWithEmptyPath\":true,\"bundledJavaVerified\":true}",
                os, arch, productVersion, nativeVersion, javaVersion,
                archiveBytes, imageBytes, archiveSha256);
        Path evidenceFile = Path.of(property("omluke.package.evidence"));
        Files.createDirectories(evidenceFile.getParent());
        Files.writeString(evidenceFile, evidence + System.lineSeparator());
        System.out.println("PACKAGE_EVIDENCE " + evidence);
    }

    private Result run(List<String> command, Path workingDirectory, boolean isolateJava) throws Exception {
        return run(command, workingDirectory, isolateJava, Map.of());
    }

    private Result run(List<String> command, Path workingDirectory, boolean isolateJava,
            Map<String, String> environment) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command).directory(workingDirectory.toFile()).redirectErrorStream(true);
        if (isolateJava) {
            Path emptyPath = Files.createTempDirectory(directory, "empty-path-");
            Path home = Files.createTempDirectory(directory, "home-");
            Path temporary = Files.createTempDirectory(directory, "tmp-");
            builder.environment().clear();
            builder.environment().put("PATH", emptyPath.toString());
            builder.environment().put("HOME", home.toString());
            builder.environment().put("TMPDIR", temporary.toString());
            builder.environment().put("LANG", "C.UTF-8");
        }
        builder.environment().putAll(environment);
        Process process = builder.start();
        try {
            assertTrue(process.waitFor(Duration.ofSeconds(30).toMillis(), TimeUnit.MILLISECONDS), "process timed out");
            byte[] output = process.getInputStream().readNBytes(64 * 1024 + 1);
            assertTrue(output.length <= 64 * 1024, "unexpectedly large process output");
            return new Result(process.exitValue(), new String(output, java.nio.charset.StandardCharsets.UTF_8));
        } finally {
            if (process.isAlive()) { process.destroyForcibly(); }
        }
    }

    private void assertSafeArchiveEntries(Path archive, String releaseRootName) throws Exception {
        Result listing = run(List.of("/usr/bin/tar", "-tzf", archive.toString()), directory, false);
        assertEquals(0, listing.exitCode(), listing.output());
        List<String> entries = listing.output().lines().filter(line -> !line.isBlank()).toList();
        assertFalse(entries.isEmpty(), "archive must not be empty");
        for (String rawEntry : entries) {
            String entry = rawEntry.endsWith("/") ? rawEntry.substring(0, rawEntry.length() - 1) : rawEntry;
            assertFalse(Path.of(entry).isAbsolute(), () -> "absolute archive entry: " + rawEntry);
            assertEquals(entry, Path.of(entry).normalize().toString(), () -> "unsafe archive entry: " + rawEntry);
            assertTrue(entry.equals(releaseRootName) || entry.startsWith(releaseRootName + "/"),
                    () -> "entry outside release root: " + rawEntry);
        }
    }

    private static void assertSafeExtractedLinks(Path releaseRoot) throws IOException {
        Path realRoot = releaseRoot.toRealPath();
        try (var paths = Files.walk(releaseRoot)) {
            for (Path path : paths.toList()) {
                if (Files.isSymbolicLink(path)) {
                    Path target = Files.readSymbolicLink(path);
                    assertFalse(target.isAbsolute(), () -> "absolute symbolic-link target: " + path + " -> " + target);
                    Path resolvedTarget = path.getParent().resolve(target).normalize();
                    assertTrue(resolvedTarget.startsWith(releaseRoot),
                            () -> "symbolic link escapes release root: " + path + " -> " + target);
                    assertTrue(resolvedTarget.toRealPath().startsWith(realRoot),
                            () -> "symbolic-link chain escapes release root: " + path + " -> " + target);
                } else if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    Number linkCount = (Number) Files.getAttribute(path, "unix:nlink", LinkOption.NOFOLLOW_LINKS);
                    assertEquals(1L, linkCount.longValue(), () -> "hard-linked archive entry: " + path);
                }
            }
        }
    }

    private static void assertChecksum(Path checksum, Path archive, String expectedHash) throws IOException {
        assertTrue(Files.isRegularFile(checksum));
        assertEquals(expectedHash + "  " + archive.getFileName(), Files.readString(checksum).strip());
    }

    private static String versionFrom(Path versionFile) throws IOException {
        return Files.readAllLines(versionFile).stream()
                .filter(line -> line.startsWith("version="))
                .map(line -> line.substring("version=".length()).strip())
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing version property: " + versionFile));
    }

    private static void assertPlatform(Path platformFile, String expectedOs, String expectedArch) throws IOException {
        List<String> lines = Files.readAllLines(platformFile);
        assertTrue(lines.contains("os=" + expectedOs), "PLATFORM must contain the target OS");
        assertTrue(lines.contains("arch=" + expectedArch), "PLATFORM must contain the target architecture");
        assertEquals(2, lines.size(), "PLATFORM must contain only os and arch");
    }

    private static void seedPreviousVersion(Path prefix, String os) throws IOException {
        Path installRoot = prefix.resolve("lib/omluke");
        Path oldVersion = installRoot.resolve("versions/0.0.0-test");
        Files.createDirectories(oldVersion);
        Files.writeString(installRoot.resolve(".owned-by-omluke"), "io.ohmyluke\n");
        Files.writeString(oldVersion.resolve("VERSION"), "version=0.0.0-test\n");
        Path oldLauncher = os.equals("macos")
                ? oldVersion.resolve("omluke.app/Contents/MacOS/omluke")
                : oldVersion.resolve("omluke/bin/omluke");
        Files.createDirectories(oldLauncher.getParent());
        Files.writeString(oldLauncher, "#!/bin/sh\nexit 0\n");
        assertTrue(oldLauncher.toFile().setExecutable(true));
        Files.createDirectories(prefix.resolve("bin"));
        Files.createSymbolicLink(prefix.resolve("bin/omluke"), oldLauncher);
    }

    private void assertUninstallerRefusesForeignDirectory(Path uninstaller, Path userHome) throws Exception {
        Path foreignPrefix = directory.resolve("foreign-prefix");
        Path foreignFile = foreignPrefix.resolve("lib/omluke/user-file.txt");
        Files.createDirectories(foreignFile.getParent());
        Files.writeString(foreignFile, "not-owned-by-omluke");
        Result refused = run(List.of("/bin/sh", uninstaller.toString(), "--prefix", foreignPrefix.toString()),
                directory, false, Map.of("HOME", userHome.toString()));
        assertNotEquals(0, refused.exitCode(), refused.output());
        assertEquals("not-owned-by-omluke", Files.readString(foreignFile));
    }

    private void assertInstallerRefusesForeignCommand(Path installer, Path userHome) throws Exception {
        Path foreignPrefix = directory.resolve("foreign-command-prefix");
        Path foreignCommand = foreignPrefix.resolve("bin/omluke");
        Files.createDirectories(foreignCommand.getParent());
        Files.writeString(foreignCommand, "user-owned-command");
        Result refused = run(List.of("/bin/sh", installer.toString(), "--prefix", foreignPrefix.toString()),
                directory, false, Map.of("HOME", userHome.toString()));
        assertNotEquals(0, refused.exitCode(), refused.output());
        assertEquals("user-owned-command", Files.readString(foreignCommand));
        assertFalse(Files.exists(foreignPrefix.resolve("lib/omluke"), LinkOption.NOFOLLOW_LINKS));
    }

    private static String property(String name) {
        String value = System.getProperty(name);
        assertNotNull(value, "missing test property: " + name);
        return value;
    }

    private static String lineValue(String output, String prefix) {
        return output.lines()
                .map(String::strip)
                .filter(line -> line.startsWith(prefix))
                .map(line -> line.substring(prefix.length()).strip())
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing output line: " + prefix + "\n" + output));
    }

    private static String tagValue(String xml, String tag) {
        String start = "<" + tag + ">";
        String end = "</" + tag + ">";
        int startIndex = xml.indexOf(start);
        int endIndex = xml.indexOf(end, startIndex + start.length());
        if (startIndex < 0 || endIndex < 0) {
            throw new AssertionError("missing package metadata tag: " + tag);
        }
        return xml.substring(startIndex + start.length(), endIndex);
    }

    private static String nativeVersionFor(String productVersion) {
        String[] components = productVersion.split("-", 2)[0].split("\\.");
        assertEquals(3, components.length, "product version must use MAJOR.MINOR.PATCH");
        return (Integer.parseInt(components[0]) + 1) + "."
                + Integer.parseInt(components[1]) + "." + Integer.parseInt(components[2]);
    }

    private void verifyMacBundle(Path image, String expectedVersion) throws Exception {
        Result signature = run(List.of("/usr/bin/codesign", "--verify", "--deep", "--strict", image.toString()),
                directory, false);
        assertEquals(0, signature.exitCode(), signature.output());
        Path info = image.resolve("Contents/Info.plist");
        for (String key : List.of("CFBundleShortVersionString", "CFBundleVersion")) {
            Result metadata = run(List.of("/usr/bin/plutil", "-extract", key, "raw", "-o", "-", info.toString()),
                    directory, false);
            assertEquals(0, metadata.exitCode(), metadata.output());
            assertEquals(expectedVersion, metadata.output().strip(), key);
        }
    }

    private static Map<String, String> symbolicLinks(Path root) throws IOException {
        Map<String, String> links = new TreeMap<>();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isSymbolicLink).toList()) {
                links.put(root.relativize(path).toString(), Files.readSymbolicLink(path).toString());
            }
        }
        return links;
    }

    private static long size(Path path) {
        try { return Files.size(path); }
        catch (IOException error) { throw new IllegalStateException("cannot measure package file", error); }
    }

    private static String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            for (int read; (read = input.read(buffer)) >= 0;) { digest.update(buffer, 0, read); }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private record Result(int exitCode, String output) {}
}
