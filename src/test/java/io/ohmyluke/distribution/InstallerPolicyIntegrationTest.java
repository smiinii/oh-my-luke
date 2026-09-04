package io.ohmyluke.distribution;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** Exercises the install/uninstall boundary without touching the real user environment. */
@EnabledOnOs({OS.LINUX, OS.MAC})
class InstallerPolicyIntegrationTest {
    private static final String VERSION = "0.1.0-rc.1";
    private static final String OWNER = "io.ohmyluke";
    private static final String LOCK_NAME = ".omluke-operation-lock";

    @TempDir Path directory;

    @Test
    void installsIntoHomeRunsVersionAndUninstallsByDefault() throws Exception {
        Path root = realTestRoot();
        Path home = Files.createDirectory(root.resolve("default-home"));
        Bundle bundle = createBundle(root.resolve("default-bundle"), hostPlatform());

        String userPath = home.resolve(".local/bin") + ":/usr/bin:/bin";
        Result installed = runScript(bundle.installer(), List.of(), home, userPath);

        assertEquals(0, installed.exitCode(), installed.output());
        assertFalse(installed.output().contains("PATH에"), installed.output());
        Path prefix = home.resolve(".local");
        Path installedLauncher = prefix.resolve("bin/omluke");
        assertTrue(Files.isSymbolicLink(installedLauncher));
        Result version = runCommand(List.of(installedLauncher.toString(), "--version"), bundle.root(), home);
        assertEquals(0, version.exitCode(), version.output());
        assertEquals("omluke " + VERSION, version.output().strip());

        Path installedUninstaller = prefix.resolve("lib/omluke/uninstall.sh");
        Result removed = runScript(installedUninstaller, List.of(), home);

        assertEquals(0, removed.exitCode(), removed.output());
        assertFalse(Files.exists(prefix.resolve("lib/omluke"), NOFOLLOW_LINKS));
        assertFalse(Files.exists(installedLauncher, NOFOLLOW_LINKS));
    }

    @Test
    void rejectsMismatchedPlatformBeforeCreatingThePrefix() throws Exception {
        Path root = realTestRoot();
        Path home = Files.createDirectory(root.resolve("mismatch-home"));
        Platform host = hostPlatform();
        Platform mismatch = host.os().equals("macos")
                ? new Platform("linux", "x64")
                : new Platform("macos", "aarch64");
        Bundle bundle = createBundle(root.resolve("mismatch-bundle"), mismatch);
        Path prefix = root.resolve("must-not-be-created");

        Result result = runScript(bundle.installer(), List.of("--prefix", prefix.toString()), home);

        assertNotEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("현재 환경"), result.output());
        assertFalse(Files.exists(prefix, NOFOLLOW_LINKS), "platform validation must happen before filesystem writes");
    }

    @Test
    void rejectsAPathLikeVersionBeforeCreatingThePrefix() throws Exception {
        Path root = realTestRoot();
        Path home = Files.createDirectory(root.resolve("unsafe-version-home"));
        Bundle bundle = createBundle(root.resolve("unsafe-version-bundle"), hostPlatform());
        Files.writeString(bundle.root().resolve("VERSION"), "version=.." + System.lineSeparator());
        Path prefix = root.resolve("must-not-be-created-for-version");

        Result result = runScript(bundle.installer(), List.of("--prefix", prefix.toString()), home);

        assertNotEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("VERSION"), result.output());
        assertFalse(Files.exists(prefix, NOFOLLOW_LINKS), "version validation must happen before filesystem writes");
    }

    @Test
    void rejectsTrailingRepeatedAndCorePrefixesForInstallAndRemoval() throws Exception {
        Path root = realTestRoot();
        Path home = Files.createDirectory(root.resolve("invalid-prefix-home"));
        Bundle bundle = createBundle(root.resolve("invalid-prefix-bundle"), hostPlatform());
        List<String> invalidPrefixes = List.of(
                root.resolve("trailing").toString() + "/",
                root.toString() + "//repeated",
                "/",
                "/bin",
                "/boot/omluke",
                "/etc",
                "/etc/omluke",
                "/proc/omluke",
                "/sbin",
                "/System",
                "/System/Library/omluke",
                "/usr",
                "/usr/bin/omluke",
                "/private/etc/omluke");

        for (String prefix : invalidPrefixes) {
            Result install = runScript(bundle.installer(), List.of("--prefix", prefix), home);
            assertNotEquals(0, install.exitCode(), () -> "installer accepted unsafe prefix: " + prefix);

            Result uninstall = runScript(bundle.uninstaller(), List.of("--prefix", prefix), home);
            assertNotEquals(0, uninstall.exitCode(), () -> "uninstaller accepted unsafe prefix: " + prefix);
        }

        assertFalse(Files.exists(root.resolve("trailing"), NOFOLLOW_LINKS));
        assertFalse(Files.exists(root.resolve("repeated"), NOFOLLOW_LINKS));
    }

    @Test
    void rejectsSymlinkedPrefixComponentsWithoutTouchingTheirTargets() throws Exception {
        Path root = realTestRoot();
        Path home = Files.createDirectory(root.resolve("symlink-prefix-home"));
        Bundle bundle = createBundle(root.resolve("symlink-prefix-bundle"), hostPlatform());

        Path externalPrefix = Files.createDirectory(root.resolve("external-prefix"));
        Path prefixSentinel = createOwnedLookingInstall(externalPrefix, "prefix target must survive");
        Path linkedPrefix = root.resolve("linked-prefix");
        Files.createSymbolicLink(linkedPrefix, externalPrefix);
        assertRejectedByBothScripts(bundle, linkedPrefix, home);
        assertEquals("prefix target must survive", Files.readString(prefixSentinel));

        Path realPrefix = Files.createDirectory(root.resolve("real-prefix"));
        Path externalLib = Files.createDirectory(root.resolve("external-lib"));
        Path libSentinel = createOwnedLookingInstallRoot(externalLib.resolve("omluke"), "lib target must survive");
        Files.createSymbolicLink(realPrefix.resolve("lib"), externalLib);
        assertRejectedByBothScripts(bundle, realPrefix, home);
        assertEquals("lib target must survive", Files.readString(libSentinel));
    }

    @Test
    void refusesMarkerAndInstalledUninstallerSymlinksWithoutOverwritingTargets() throws Exception {
        Path root = realTestRoot();
        Path home = Files.createDirectory(root.resolve("owned-file-home"));
        Bundle bundle = createBundle(root.resolve("owned-file-bundle"), hostPlatform());
        Path prefix = root.resolve("owned-file-prefix");
        Path installRoot = Files.createDirectories(prefix.resolve("lib/omluke"));

        Path externalMarker = root.resolve("external-marker.txt");
        Files.writeString(externalMarker, "external marker");
        Path marker = installRoot.resolve(".owned-by-omluke");
        Files.createSymbolicLink(marker, externalMarker);

        Result markerInstall = runScript(bundle.installer(), List.of("--prefix", prefix.toString()), home);
        Result markerRemoval = runScript(bundle.uninstaller(), List.of("--prefix", prefix.toString()), home);
        assertNotEquals(0, markerInstall.exitCode(), markerInstall.output());
        assertNotEquals(0, markerRemoval.exitCode(), markerRemoval.output());
        assertEquals("external marker", Files.readString(externalMarker));
        assertTrue(Files.isSymbolicLink(marker));

        Files.delete(marker);
        Files.writeString(marker, OWNER + System.lineSeparator());
        Path externalUninstaller = root.resolve("external-uninstall.sh");
        Files.writeString(externalUninstaller, "external uninstaller");
        Path installedUninstaller = installRoot.resolve("uninstall.sh");
        Files.createSymbolicLink(installedUninstaller, externalUninstaller);

        Result uninstallerInstall = runScript(bundle.installer(), List.of("--prefix", prefix.toString()), home);
        assertNotEquals(0, uninstallerInstall.exitCode(), uninstallerInstall.output());
        assertTrue(uninstallerInstall.output().contains("심볼릭 링크"), uninstallerInstall.output());
        assertEquals("external uninstaller", Files.readString(externalUninstaller));
        assertTrue(Files.isSymbolicLink(installedUninstaller));
    }

    @Test
    void doesNotTreatATraversingCommandLinkAsOwned() throws Exception {
        Path root = realTestRoot();
        Path home = Files.createDirectory(root.resolve("traversing-link-home"));
        Bundle bundle = createBundle(root.resolve("traversing-link-bundle"), hostPlatform());
        Path prefix = root.resolve("traversing-link-prefix");
        Path installRoot = Files.createDirectories(prefix.resolve("lib/omluke"));
        Files.writeString(installRoot.resolve(".owned-by-omluke"), OWNER + System.lineSeparator());
        Path bin = Files.createDirectories(prefix.resolve("bin"));
        Path command = bin.resolve("omluke");
        Path unsafeTarget = Path.of(installRoot + "/versions/../user-command");
        Files.createSymbolicLink(command, unsafeTarget);

        Result install = runScript(bundle.installer(), List.of("--prefix", prefix.toString()), home);
        assertNotEquals(0, install.exitCode(), install.output());
        assertEquals(unsafeTarget, Files.readSymbolicLink(command));

        Result removal = runScript(bundle.uninstaller(), List.of("--prefix", prefix.toString()), home);
        assertEquals(0, removal.exitCode(), removal.output());
        assertTrue(Files.isSymbolicLink(command), "uninstall must preserve a command link it does not own");
        assertEquals(unsafeTarget, Files.readSymbolicLink(command));
    }

    @Test
    void doesNotReplaceACommandLinkThatPointsToADirectory() throws Exception {
        Path root = realTestRoot();
        Path home = Files.createDirectory(root.resolve("directory-link-home"));
        Bundle bundle = createBundle(root.resolve("directory-link-bundle"), hostPlatform());
        Path prefix = root.resolve("directory-link-prefix");
        Path installRoot = Files.createDirectories(prefix.resolve("lib/omluke"));
        Files.writeString(installRoot.resolve(".owned-by-omluke"), OWNER + System.lineSeparator());
        Path directoryTarget = Files.createDirectories(
                installRoot.resolve("versions/0.1.0-rc.1/omluke/bin/omluke"));
        Path command = Files.createDirectories(prefix.resolve("bin")).resolve("omluke");
        Files.createSymbolicLink(command, directoryTarget);

        Result install = runScript(bundle.installer(), List.of("--prefix", prefix.toString()), home);
        assertNotEquals(0, install.exitCode(), install.output());
        assertTrue(Files.isSymbolicLink(command));
        assertEquals(directoryTarget, Files.readSymbolicLink(command));

        Result removal = runScript(bundle.uninstaller(), List.of("--prefix", prefix.toString()), home);
        assertEquals(0, removal.exitCode(), removal.output());
        assertTrue(Files.isSymbolicLink(command), "uninstall must preserve a link to a directory");
        assertEquals(directoryTarget, Files.readSymbolicLink(command));
    }

    @Test
    void ignoresCommandsInjectedAheadOfTheTrustedSystemPath() throws Exception {
        Path root = realTestRoot();
        Path home = Files.createDirectory(root.resolve("hostile-path-home"));
        Bundle bundle = createBundle(root.resolve("hostile-path-bundle"), hostPlatform());
        Path hostileBin = Files.createDirectory(root.resolve("hostile-bin"));
        Path sentinel = root.resolve("hostile-command-ran");
        for (String command : List.of("uname", "cp", "mv", "rm")) {
            Path fake = hostileBin.resolve(command);
            Files.writeString(fake, "#!/bin/sh\nprintf invoked > '" + sentinel + "'\nexit 99\n");
            assertTrue(fake.toFile().setExecutable(true, false));
        }
        String hostilePath = hostileBin + ":/usr/bin:/bin";

        Result installed = runScript(bundle.installer(), List.of(), home, hostilePath);
        assertEquals(0, installed.exitCode(), installed.output());
        Path installedUninstaller = home.resolve(".local/lib/omluke/uninstall.sh");
        Result removed = runScript(installedUninstaller, List.of(), home, hostilePath);
        assertEquals(0, removed.exitCode(), removed.output());
        assertFalse(Files.exists(sentinel), "lifecycle scripts must not use commands injected through PATH");
    }

    @Test
    void refusesInstallAndRemovalWhileAnotherOperationHoldsTheLock() throws Exception {
        Path root = realTestRoot();
        Path home = Files.createDirectory(root.resolve("lock-home"));
        Bundle bundle = createBundle(root.resolve("lock-bundle"), hostPlatform());

        Path installPrefix = root.resolve("install-lock-prefix");
        Files.createDirectories(installPrefix.resolve("lib").resolve(LOCK_NAME));
        Result blockedInstall = runScript(bundle.installer(), List.of("--prefix", installPrefix.toString()), home);
        assertNotEquals(0, blockedInstall.exitCode(), blockedInstall.output());
        assertTrue(blockedInstall.output().contains("진행 중"), blockedInstall.output());
        assertFalse(Files.exists(installPrefix.resolve("lib/omluke"), NOFOLLOW_LINKS));

        Path removalPrefix = root.resolve("removal-lock-prefix");
        Result installed = runScript(bundle.installer(), List.of("--prefix", removalPrefix.toString()), home);
        assertEquals(0, installed.exitCode(), installed.output());
        Path installedUninstaller = removalPrefix.resolve("lib/omluke/uninstall.sh");
        Files.createDirectory(removalPrefix.resolve("lib").resolve(LOCK_NAME));

        Result blockedRemoval = runScript(installedUninstaller,
                List.of("--prefix", removalPrefix.toString()), home);
        assertNotEquals(0, blockedRemoval.exitCode(), blockedRemoval.output());
        assertTrue(blockedRemoval.output().contains("진행 중"), blockedRemoval.output());
        assertTrue(Files.isRegularFile(removalPrefix.resolve("lib/omluke/.owned-by-omluke")));
        assertTrue(Files.isSymbolicLink(removalPrefix.resolve("bin/omluke")));
    }

    private void assertRejectedByBothScripts(Bundle bundle, Path prefix, Path home) throws Exception {
        Result install = runScript(bundle.installer(), List.of("--prefix", prefix.toString()), home);
        assertNotEquals(0, install.exitCode(), install.output());
        assertTrue(install.output().contains("심볼릭 링크"), install.output());

        Result uninstall = runScript(bundle.uninstaller(), List.of("--prefix", prefix.toString()), home);
        assertNotEquals(0, uninstall.exitCode(), uninstall.output());
        assertTrue(uninstall.output().contains("심볼릭 링크"), uninstall.output());
    }

    private static Path createOwnedLookingInstall(Path prefix, String sentinelContents) throws IOException {
        return createOwnedLookingInstallRoot(prefix.resolve("lib/omluke"), sentinelContents);
    }

    private static Path createOwnedLookingInstallRoot(Path installRoot, String sentinelContents) throws IOException {
        Files.createDirectories(installRoot);
        Files.writeString(installRoot.resolve(".owned-by-omluke"), OWNER + System.lineSeparator());
        Path sentinel = installRoot.resolve("sentinel.txt");
        Files.writeString(sentinel, sentinelContents);
        return sentinel;
    }

    private Bundle createBundle(Path bundleRoot, Platform platform) throws Exception {
        Files.createDirectory(bundleRoot);
        Path installer = copyScript("packaging/install.sh", bundleRoot.resolve("install.sh"));
        Path uninstaller = copyScript("packaging/uninstall.sh", bundleRoot.resolve("uninstall.sh"));
        Files.writeString(bundleRoot.resolve("VERSION"), "version=" + VERSION + System.lineSeparator());
        Files.writeString(bundleRoot.resolve("PLATFORM"),
                "os=" + platform.os() + System.lineSeparator()
                        + "arch=" + platform.arch() + System.lineSeparator());

        String launcherSuffix;
        if (platform.os().equals("macos")) {
            launcherSuffix = "omluke.app/Contents/MacOS/omluke";
        } else {
            launcherSuffix = "omluke/bin/omluke";
        }
        Path launcher = bundleRoot.resolve(launcherSuffix);
        Files.createDirectories(launcher.getParent());
        Files.writeString(launcher, "#!/bin/sh\n"
                + "if [ \"${1:-}\" = \"--version\" ]; then\n"
                + "  printf '%s\\n' 'omluke " + VERSION + "'\n"
                + "  exit 0\n"
                + "fi\n"
                + "exit 64\n");
        assertTrue(launcher.toFile().setExecutable(true, false));
        return new Bundle(bundleRoot, installer, uninstaller);
    }

    private static Path copyScript(String source, Path destination) throws IOException {
        Path sourcePath = Path.of(source).toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(sourcePath), "missing lifecycle script: " + sourcePath);
        Files.copy(sourcePath, destination, StandardCopyOption.REPLACE_EXISTING);
        assertTrue(destination.toFile().setExecutable(true, false));
        return destination;
    }

    private Path realTestRoot() throws IOException {
        return directory.toRealPath();
    }

    private static Platform hostPlatform() throws Exception {
        String kernel = commandOutput("/usr/bin/uname", "-s");
        String machine = commandOutput("/usr/bin/uname", "-m");
        String os = switch (kernel) {
            case "Darwin" -> "macos";
            case "Linux" -> "linux";
            default -> throw new IllegalStateException("unsupported test OS: " + kernel);
        };
        String arch = switch (machine) {
            case "arm64", "aarch64" -> "aarch64";
            case "x86_64", "amd64" -> "x64";
            default -> throw new IllegalStateException("unsupported test architecture: " + machine);
        };
        return new Platform(os, arch);
    }

    private static String commandOutput(String... command) throws Exception {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        assertTrue(process.waitFor(Duration.ofSeconds(5).toMillis(), TimeUnit.MILLISECONDS),
                "platform command timed out");
        assertEquals(0, process.exitValue());
        return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
    }

    private Result runScript(Path script, List<String> arguments, Path home) throws Exception {
        return runScript(script, arguments, home, "/usr/bin:/bin");
    }

    private Result runScript(Path script, List<String> arguments, Path home, String path) throws Exception {
        List<String> command = new ArrayList<>();
        command.add("/bin/sh");
        command.add(script.toString());
        command.addAll(arguments);
        return runCommand(command, script.getParent(), home, path);
    }

    private static Result runCommand(List<String> command, Path workingDirectory, Path home) throws Exception {
        return runCommand(command, workingDirectory, home, "/usr/bin:/bin");
    }

    private static Result runCommand(
            List<String> command, Path workingDirectory, Path home, String path) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(workingDirectory.toFile())
                .redirectErrorStream(true);
        Map<String, String> environment = builder.environment();
        environment.clear();
        environment.put("HOME", home.toString());
        environment.put("PATH", path);
        environment.put("LANG", "C");
        environment.put("LC_ALL", "C");
        Process process = builder.start();
        try {
            assertTrue(process.waitFor(Duration.ofSeconds(10).toMillis(), TimeUnit.MILLISECONDS),
                    "lifecycle script timed out: " + String.join(" ", command));
            byte[] output = process.getInputStream().readNBytes(64 * 1024 + 1);
            assertTrue(output.length <= 64 * 1024, "unexpectedly large lifecycle script output");
            return new Result(process.exitValue(), new String(output, StandardCharsets.UTF_8));
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private record Platform(String os, String arch) {}

    private record Bundle(Path root, Path installer, Path uninstaller) {}

    private record Result(int exitCode, String output) {}
}
