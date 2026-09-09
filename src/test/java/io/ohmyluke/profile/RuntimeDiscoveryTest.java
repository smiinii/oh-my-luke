package io.ohmyluke.profile;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RuntimeDiscoveryTest {
    @TempDir Path root;

    @Test void discoveryOnlyInspectsKnownExecutableFilesAndNeverRunsThem() throws Exception {
        Path bin = Files.createDirectory(root.resolve("bin"));
        Path project = Files.createDirectory(root.resolve("project"));
        executable(bin.resolve("codex")); executable(bin.resolve("omx"));
        Files.writeString(bin.resolve("claude"), "not executable");
        var found = new RuntimeDiscovery(bin.toString(), project).scan();
        assertEquals(6, found.size());
        assertEquals(RuntimeDiscovery.State.FOUND, found.get(0).state());
        assertEquals(bin.resolve("codex").toRealPath(), found.get(0).executable());
        assertEquals(RuntimeDiscovery.State.MISSING, found.get(1).state());
        assertEquals(RuntimeDiscovery.State.FOUND, found.get(3).state());
        assertEquals("codex", found.get(3).runtime());
    }

    @Test void shadowingProjectExecutableIsBlockedInsteadOfSilentlyChoosingAnotherCodex() throws Exception {
        Path project = Files.createDirectory(root.resolve("project"));
        Path bin = Files.createDirectory(root.resolve("bin"));
        executable(project.resolve("codex")); executable(bin.resolve("codex"));
        var found = new RuntimeDiscovery(project + java.io.File.pathSeparator + bin, project).scan().get(0);
        assertEquals(RuntimeDiscovery.State.BLOCKED, found.state());
        assertNull(found.executable());
    }

    @Test void absentAndOversizedPathDoNotFallBackToTheCurrentFolder() throws Exception {
        executable(root.resolve("codex"));
        assertEquals(RuntimeDiscovery.State.MISSING, new RuntimeDiscovery(null, root).scan().get(0).state());
        assertEquals(RuntimeDiscovery.State.BLOCKED, new RuntimeDiscovery("x".repeat(32_769), root).scan().get(0).state());
    }

    @Test void relativeAndSymlinkedProjectExecutablesCannotBypassDiscoveryGuard() throws Exception {
        Path project = Files.createDirectory(root.resolve("project"));
        Path bin = Files.createDirectory(root.resolve("bin"));
        executable(project.resolve("codex"));
        Path relative = Path.of("").toAbsolutePath().relativize(project.toAbsolutePath());
        assertEquals(RuntimeDiscovery.State.BLOCKED, new RuntimeDiscovery(relative.toString(), project).scan().get(0).state());
        Files.createSymbolicLink(bin.resolve("codex"), project.resolve("codex"));
        assertEquals(RuntimeDiscovery.State.BLOCKED, new RuntimeDiscovery(bin.toString(), project).scan().get(0).state());
    }

    private void executable(Path path) throws Exception {
        Files.writeString(path, "THIS FILE MUST NEVER EXECUTE");
        assertTrue(path.toFile().setExecutable(true, true));
    }
}
