package io.ohmyluke.distribution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@EnabledOnOs({OS.LINUX, OS.MAC})
class ReleaseBundleAssemblerTest {
    private static final String VERSION = "0.1.0-rc.1";
    @TempDir Path directory;
    Path input;

    @BeforeEach
    void requireJqAndCreateInput() throws Exception {
        assumeTrue(commandSucceeds("jq", "--version"), "jq is required by the release assembly script");
        input = Files.createDirectory(directory.resolve("input"));
        Files.createDirectory(input.resolve("evidence"));
        createAsset("linux-x64", "linux", "x64", "linux archive");
        createAsset("macos-aarch64", "macos", "aarch64", "mac archive");
    }

    @Test
    void assemblesExactlyTwoVerifiedArchives() throws Exception {
        Path output = directory.resolve("output");
        Result assembled = assemble(output);

        assertEquals(0, assembled.exitCode(), assembled.output());
        assertTrue(Files.isRegularFile(output.resolve("omluke-" + VERSION + "-linux-x64.tar.gz")));
        assertTrue(Files.isRegularFile(output.resolve("omluke-" + VERSION + "-macos-aarch64.tar.gz")));
        assertTrue(Files.isRegularFile(output.resolve("omluke-" + VERSION + "-linux-x64.tar.gz.sha256")));
        assertTrue(Files.isRegularFile(output.resolve("omluke-" + VERSION + "-macos-aarch64.tar.gz.sha256")));
        assertTrue(Files.isRegularFile(output.resolve("omluke-" + VERSION + "-linux-x64.json")));
        assertTrue(Files.isRegularFile(output.resolve("omluke-" + VERSION + "-macos-aarch64.json")));
        List<String> sums = Files.readAllLines(output.resolve("SHA256SUMS"));
        assertEquals(2, sums.size());
        assertTrue(sums.get(0).endsWith("omluke-" + VERSION + "-linux-x64.tar.gz"));
        assertTrue(sums.get(1).endsWith("omluke-" + VERSION + "-macos-aarch64.tar.gz"));
    }

    @Test
    void rejectsTheFirstTamperedAssetWithoutLeavingAPartialBundle() throws Exception {
        Files.writeString(input.resolve("omluke-" + VERSION + "-linux-x64.tar.gz"), "tampered");
        Path rejectedOutput = directory.resolve("rejected-output");
        Result rejected = assemble(rejectedOutput);
        assertEquals(1, rejected.exitCode(), rejected.output());
        assertTrue(rejected.output().contains("체크섬이 일치하지 않습니다"), rejected.output());
        assertFalse(Files.exists(rejectedOutput), "a rejected bundle must not leave publishable files");
    }

    @Test
    void rejectsTheSecondTamperedAssetWithoutLeavingAPartialBundle() throws Exception {
        Files.writeString(input.resolve("omluke-" + VERSION + "-macos-aarch64.tar.gz"), "tampered");
        Path rejectedOutput = directory.resolve("rejected-output");
        Result rejected = assemble(rejectedOutput);
        assertEquals(1, rejected.exitCode(), rejected.output());
        assertTrue(rejected.output().contains("체크섬이 일치하지 않습니다"), rejected.output());
        assertFalse(Files.exists(rejectedOutput), "validation must finish before copying the first asset");
    }

    @Test
    void rejectsASidecarWithAdditionalLines() throws Exception {
        Path sidecar = input.resolve("omluke-" + VERSION + "-linux-x64.tar.gz.sha256");
        Files.writeString(sidecar, Files.readString(sidecar) + "unexpected second line\n");
        Path rejectedOutput = directory.resolve("rejected-output");
        Result rejected = assemble(rejectedOutput);
        assertEquals(1, rejected.exitCode(), rejected.output());
        assertTrue(rejected.output().contains("정확히 한 줄"), rejected.output());
        assertFalse(Files.exists(rejectedOutput), "an invalid sidecar must not create an output directory");
    }

    private void createAsset(String classifier, String os, String arch, String contents) throws Exception {
        String baseName = "omluke-" + VERSION + "-" + classifier;
        Path archive = input.resolve(baseName + ".tar.gz");
        Files.writeString(archive, contents);
        String hash = sha256(archive);
        Files.writeString(input.resolve(baseName + ".tar.gz.sha256"),
                hash + "  " + archive.getFileName() + System.lineSeparator());
        Files.writeString(input.resolve("evidence").resolve(baseName + ".json"), String.format(
                "{\"os\":\"%s\",\"arch\":\"%s\",\"productVersion\":\"%s\","
                        + "\"sha256\":\"%s\",\"ranWithEmptyPath\":true,\"bundledJavaVerified\":true}%n",
                os, arch, VERSION, hash));
    }

    private Result assemble(Path output) throws Exception {
        Path script = Path.of("scripts/release/assemble-bundle.sh").toAbsolutePath();
        Process process = new ProcessBuilder("/bin/sh", script.toString(), VERSION,
                input.toString(), output.toString()).redirectErrorStream(true).start();
        assertTrue(process.waitFor(10, TimeUnit.SECONDS), "release bundle assembly timed out");
        return new Result(process.exitValue(), new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
    }

    private static boolean commandSucceeds(String... command) {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            return process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (IOException | InterruptedException error) {
            if (error instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(Files.readAllBytes(file));
        return HexFormat.of().formatHex(digest.digest());
    }

    private record Result(int exitCode, String output) {}
}
