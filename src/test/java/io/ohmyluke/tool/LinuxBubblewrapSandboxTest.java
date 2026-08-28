package io.ohmyluke.tool;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@DisabledOnOs(OS.WINDOWS)
class LinuxBubblewrapSandboxTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void preparesNetworkIsolatedAndNetworkApprovedCommands() throws IOException {
        Path bwrap = Files.writeString(temporaryDirectory.resolve("bwrap"), "fixture");
        assertTrue(bwrap.toFile().setExecutable(true));
        Path workspace = Files.createDirectory(temporaryDirectory.resolve("workspace"));
        Path home = Files.createDirectory(temporaryDirectory.resolve("home"));
        LinuxBubblewrapSandbox sandbox = new LinuxBubblewrapSandbox(bwrap);
        ProcessSandboxSpec local = new ProcessSandboxSpec(
                Path.of("/usr/bin/true"),
                List.of(),
                workspace,
                workspace,
                home,
                false);
        ProcessSandboxSpec network = new ProcessSandboxSpec(
                Path.of("/usr/bin/true"),
                List.of(),
                workspace,
                workspace,
                home,
                true);

        List<String> localCommand = sandbox.prepare(local).command();
        List<String> networkCommand = sandbox.prepare(network).command();

        assertTrue(localCommand.contains("--unshare-net"));
        assertFalse(networkCommand.contains("--unshare-net"));
        assertTrue(localCommand.contains("--die-with-parent"));
        assertTrue(localCommand.contains("--as-pid-1"));
        assertTrue(localCommand.contains(temporaryDirectory.toString()));
    }
}
