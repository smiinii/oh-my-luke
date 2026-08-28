package io.ohmyluke.tool;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ohmyluke.policy.PermissionGrantLedger;
import io.ohmyluke.policy.ToolPermission;
import io.ohmyluke.policy.ToolPermissionGrant;
import io.ohmyluke.policy.ToolPermissionPolicy;
import io.ohmyluke.policy.ToolPermissionRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileToolTest {
    private static final Instant NOW = Instant.parse("2026-08-28T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @TempDir
    Path temporaryDirectory;

    @Test
    void readsWritesAndDeletesOrdinaryProjectFilesWithoutPrompting() throws IOException {
        Path project = Files.createDirectory(temporaryDirectory.resolve("project"));
        Path source = Files.writeString(project.resolve("source.txt"), "before");
        FileTool tool = tool(project, false, List.of());

        FileToolResult read = tool.execute(FileToolRequest.read("read-1", source));
        FileToolResult write = tool.execute(FileToolRequest.write(
                "write-1",
                source,
                "after".getBytes(StandardCharsets.UTF_8)));
        FileToolResult delete = tool.execute(FileToolRequest.delete("delete-1", source));

        assertEquals(ToolPermission.ALLOW, read.permission().permission());
        assertArrayEquals("before".getBytes(StandardCharsets.UTF_8), read.content());
        assertEquals(ToolPermission.ALLOW, write.permission().permission());
        assertEquals("write-1", write.checkpointId());
        assertEquals(ToolPermission.ALLOW, delete.permission().permission());
        assertFalse(Files.exists(source));
    }

    @Test
    void restoresAFileMutationFromItsCheckpoint() throws IOException {
        Path project = Files.createDirectory(temporaryDirectory.resolve("project"));
        Path source = Files.writeString(project.resolve("source.txt"), "before");
        FileTool tool = tool(project, false, List.of());

        FileToolResult result = tool.execute(FileToolRequest.write(
                "write-1",
                source,
                "after".getBytes(StandardCharsets.UTF_8)));
        tool.restore(result.checkpointId());

        assertEquals("before", Files.readString(source));
    }

    @Test
    void blocksOmlAndSecretReadsEvenInAutonomousMode() throws IOException {
        Path project = Files.createDirectory(temporaryDirectory.resolve("project"));
        Path permissions = Files.createDirectories(project.resolve(".oml"))
                .resolve("permissions.json");
        Files.writeString(permissions, "do not change");
        Path secret = Files.writeString(project.resolve(".env"), "TOKEN=secret");
        FileTool tool = tool(project, true, List.of());

        FileToolResult policyMutation = tool.execute(FileToolRequest.write(
                "write-policy",
                permissions,
                "changed".getBytes(StandardCharsets.UTF_8)));
        FileToolResult secretRead = tool.execute(FileToolRequest.read("read-secret", secret));

        assertEquals(ToolPermission.DENY, policyMutation.permission().permission());
        assertEquals(ToolPermission.DENY, secretRead.permission().permission());
        assertEquals("do not change", Files.readString(permissions));
        assertEquals(0, secretRead.content().length);
    }

    @Test
    void neverMutatesProjectMetadataOrDeletesTheProjectRoot() throws IOException {
        Path project = Files.createDirectory(temporaryDirectory.resolve("project"));
        Path gitConfig = Files.createDirectories(project.resolve(".git")).resolve("config");
        Files.writeString(gitConfig, "original");
        FileTool tool = tool(project, true, List.of());

        FileToolResult gitWrite = tool.execute(FileToolRequest.write(
                "write-git",
                gitConfig,
                "changed".getBytes(StandardCharsets.UTF_8)));
        FileToolResult rootDelete = tool.execute(FileToolRequest.delete("delete-root", project));

        assertEquals(ToolPermission.DENY, gitWrite.permission().permission());
        assertEquals(ToolPermission.DENY, rootDelete.permission().permission());
        assertEquals("original", Files.readString(gitConfig));
        assertTrue(Files.isDirectory(project));
    }

    @Test
    void asksBeforeOutsideProjectAccessAndAcceptsAnExactProjectGrant() throws IOException {
        Path project = Files.createDirectory(temporaryDirectory.resolve("project"));
        Path outside = Files.writeString(temporaryDirectory.resolve("outside.txt"), "outside");
        FileTool first = tool(project, false, List.of());
        FileToolRequest request = FileToolRequest.read("read-outside", outside);

        FileToolResult asked = first.execute(request);
        ToolPermissionRequest permissionRequest = first.permissionRequest(request);
        ToolPermissionGrant grant = ToolPermissionGrant.forProject(
                "grant-outside",
                permissionRequest,
                NOW.plusSeconds(60).toEpochMilli());
        FileTool approved = tool(project, false, List.of(grant));
        FileToolResult allowed = approved.execute(request);

        assertEquals(ToolPermission.ASK, asked.permission().permission());
        assertEquals(ToolPermission.ALLOW, allowed.permission().permission());
        assertArrayEquals("outside".getBytes(StandardCharsets.UTF_8), allowed.content());
    }

    @Test
    void blocksSymlinkTraversalWithoutReadingItsTarget() throws IOException {
        Path project = Files.createDirectory(temporaryDirectory.resolve("project"));
        Path outside = Files.writeString(temporaryDirectory.resolve("secret.txt"), "secret");
        Path link = Files.createSymbolicLink(project.resolve("link.txt"), outside);
        FileTool tool = tool(project, false, List.of());

        FileToolResult result = tool.execute(FileToolRequest.read("read-link", link));

        assertEquals(ToolPermission.DENY, result.permission().permission());
        assertEquals("file.symlink-deny", result.permission().reasonCode());
        assertEquals(0, result.content().length);
    }

    @Test
    void asksForRecursiveDeleteThenRestoresTheApprovedTree() throws IOException {
        Path project = Files.createDirectory(temporaryDirectory.resolve("project"));
        Path generated = Files.createDirectories(project.resolve("generated/nested"));
        Files.writeString(generated.resolve("one.txt"), "one");
        FileToolRequest request = FileToolRequest.delete("delete-tree", project.resolve("generated"));
        FileTool first = tool(project, false, List.of());

        FileToolResult asked = first.execute(request);
        ToolPermissionGrant grant = ToolPermissionGrant.once(
                "grant-delete",
                first.permissionRequest(request),
                NOW.plusSeconds(60).toEpochMilli());
        FileTool approved = tool(project, false, List.of(grant));
        FileToolResult deleted = approved.execute(request);
        approved.restore(deleted.checkpointId());

        assertEquals(ToolPermission.ASK, asked.permission().permission());
        assertEquals(ToolPermission.ALLOW, deleted.permission().permission());
        assertTrue(Files.exists(project.resolve("generated/nested/one.txt")));
        assertEquals("one", Files.readString(project.resolve("generated/nested/one.txt")));
    }

    @Test
    void createsDirectoriesAndMovesFilesWithRollback() throws IOException {
        Path project = Files.createDirectory(temporaryDirectory.resolve("project"));
        Path source = Files.writeString(project.resolve("source.txt"), "source");
        FileTool tool = tool(project, false, List.of());

        FileToolResult directory = tool.execute(FileToolRequest.createDirectory(
                "mkdir-1",
                project.resolve("target")));
        FileToolResult moved = tool.execute(FileToolRequest.move(
                "move-1",
                source,
                project.resolve("target/moved.txt")));
        tool.restore(moved.checkpointId());
        tool.restore(directory.checkpointId());

        assertEquals(ToolPermission.ALLOW, directory.permission().permission());
        assertEquals(ToolPermission.ALLOW, moved.permission().permission());
        assertTrue(Files.exists(source));
        assertFalse(Files.exists(project.resolve("target")));
    }

    private static FileTool tool(
            Path project,
            boolean autonomous,
            List<ToolPermissionGrant> grants) {
        ToolPermissionPolicy permissions = new ToolPermissionPolicy(
                new PermissionGrantLedger(grants),
                autonomous,
                CLOCK);
        return new FileTool(project, "run-001", permissions, CLOCK);
    }
}
