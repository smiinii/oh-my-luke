package io.ohmyluke.distribution;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
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
        Path sourceImage = Path.of(property("omluke.package.sourceImage"));
        String imageName = property("omluke.package.imageName");
        assertTrue(Files.isRegularFile(archive));

        Path extracted = Files.createDirectory(directory.resolve("extracted"));
        Result extraction = run(List.of("/usr/bin/tar", "-xzf", archive.toString(), "-C", extracted.toString()), directory, false);
        assertEquals(0, extraction.exitCode(), extraction.output());
        Path image = extracted.resolve(imageName);
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
        String productVersion = property("omluke.package.productVersion");
        assertEquals(nativeVersionFor(productVersion), nativeVersion);
        if (os.equals("macos")) {
            verifyMacBundle(image, nativeVersion);
        }

        Path helpProject = Files.createDirectory(directory.resolve("help-project"));
        Result help = run(List.of(launcher.toString()), helpProject, true);
        assertEquals(0, help.exitCode(), help.output());
        assertTrue(help.output().contains("Oh My Luke"), help.output());
        assertTrue(help.output().contains("사용법: omluke start"), help.output());

        Path workflowProject = Files.createDirectory(directory.resolve("workflow-project"));
        Files.copy(Path.of("examples/workflows/check-and-approve.json"), workflowProject.resolve("workflow.json"));
        Files.copy(Path.of("examples/workflows/ready.txt"), workflowProject.resolve("hello.txt"));
        Result waiting = run(List.of(launcher.toString(), "workflow", "workflow.json", "--run-id", "package-test"),
                workflowProject, true);
        assertEquals(3, waiting.exitCode(), waiting.output());
        assertTrue(waiting.output().contains("result=WAITING_APPROVAL"), waiting.output());
        assertTrue(waiting.output().contains("aiAttempts=0"), waiting.output());
        assertTrue(waiting.output().contains("recordedUsage=0"), waiting.output());
        String requestId = lineValue(waiting.output(), "approvalRequestId=");

        Result approved = run(List.of(launcher.toString(), "approve", "package-test", requestId), workflowProject, true);
        assertEquals(0, approved.exitCode(), approved.output());
        Result completed = run(List.of(launcher.toString(), "resume", "package-test"), workflowProject, true);
        assertEquals(0, completed.exitCode(), completed.output());
        assertTrue(completed.output().contains("result=SUCCEEDED"), completed.output());
        assertTrue(completed.output().contains("aiAttempts=0"), completed.output());
        assertTrue(completed.output().contains("recordedUsage=0"), completed.output());
        assertTrue(Files.isRegularFile(workflowProject.resolve(".oml/runs/package-test/state.json")));

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
                        + "\"externalJavaAvailable\":false}",
                os, arch, productVersion, nativeVersion, javaVersion,
                archiveBytes, imageBytes, sha256(archive));
        Path evidenceFile = Path.of(property("omluke.package.evidence"));
        Files.createDirectories(evidenceFile.getParent());
        Files.writeString(evidenceFile, evidence + System.lineSeparator());
        System.out.println("PACKAGE_EVIDENCE " + evidence);
    }

    private Result run(List<String> command, Path workingDirectory, boolean isolateJava) throws Exception {
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
