package io.ohmyluke.preset;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PresetContentStoreTest {
    @TempDir Path project;
    @Test void roundTrip() {
        PresetContentStore store = new PresetContentStore(project, "test");
        byte[] content = "old".getBytes(StandardCharsets.UTF_8);
        assertArrayEquals(content, store.read(store.save(content)));
    }
    @Test void fileToolRead() throws Exception {
        java.nio.file.Files.writeString(project.resolve("hello.txt"), "old");
        var tool = new io.ohmyluke.tool.FileTool(project, "test",
                request -> io.ohmyluke.policy.ToolPermissionDecision.allow("test.allow", "allowed", null), java.time.Clock.systemUTC());
        var read = tool.execute(io.ohmyluke.tool.FileToolRequest.read("preset-prepare-0", Path.of("hello.txt")));
        assertTrue(read.executed(), read.detail());
    }

    @Test void refusesCorruptAndSymlinkedSnapshots() throws Exception {
        PresetContentStore store = new PresetContentStore(project, "test");
        String hash = store.save("old".getBytes(StandardCharsets.UTF_8));
        Path artifact = project.resolve(".oml/runs/test/artifacts/preset-content/" + hash + ".txt");
        java.nio.file.Files.writeString(artifact, "tampered");
        assertThrows(IllegalStateException.class, () -> store.read(hash));
        java.nio.file.Files.delete(artifact);
        Path other = java.nio.file.Files.writeString(project.resolve("other.txt"), "old");
        java.nio.file.Files.createSymbolicLink(artifact, other);
        assertThrows(RuntimeException.class, () -> store.read(hash));
        assertThrows(RuntimeException.class, () -> store.save("old".getBytes(StandardCharsets.UTF_8)));
        assertEquals("old", java.nio.file.Files.readString(other));
    }

    @Test void rejectsBinaryInvalidUtf8AndOversizedContent() {
        assertThrows(IllegalArgumentException.class, () -> PresetContentStore.text(new byte[] {0}));
        assertThrows(IllegalArgumentException.class, () -> PresetContentStore.text(new byte[] {(byte) 0xff}));
        assertThrows(IllegalArgumentException.class, () -> PresetContentStore.text(new byte[65_537]));
    }
}
