package io.ohmyluke.tool;

import static org.junit.jupiter.api.Assertions.*;
import io.ohmyluke.policy.ToolPermissionDecision;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConditionalFileWriteTest {
    @TempDir Path project;

    @Test void appliedReplayIsIdempotentAndCheckpointRestoresOriginal() throws Exception {
        Path file = Files.writeString(project.resolve("file.txt"), "before");
        FileTool tool = tool();
        var request = FileToolRequest.write("apply-1", file, bytes("after"));
        assertTrue(tool.writeIfUnchanged(request, bytes("before")).executed());
        assertTrue(tool.writeIfUnchanged(request, bytes("before")).executed());
        tool.restore("apply-1");
        assertEquals("before", Files.readString(file));
    }

    @Test void conflictDoesNotReplaceOrRollbackExternalEdit() throws Exception {
        Path file = Files.writeString(project.resolve("file.txt"), "user change");
        var result = tool().writeIfUnchanged(FileToolRequest.write("apply-1", file, bytes("after")), bytes("before"));
        assertFalse(result.executed());
        assertEquals("file.content-conflict", result.permission().reasonCode());
        assertEquals("user change", Files.readString(file));
    }

    @Test void replayAfterExternalChangeDoesNotOverwriteIt() throws Exception {
        Path file = Files.writeString(project.resolve("file.txt"), "before");
        FileTool tool = tool();
        var request = FileToolRequest.write("apply-1", file, bytes("after"));
        assertTrue(tool.writeIfUnchanged(request, bytes("before")).executed());
        Files.writeString(file, "user change");
        assertFalse(tool.writeIfUnchanged(request, bytes("before")).executed());
        assertEquals("user change", Files.readString(file));
    }

    private FileTool tool() {
        return new FileTool(project, "test", request -> ToolPermissionDecision.allow("test.allow", "allowed", null), Clock.systemUTC());
    }
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
}
