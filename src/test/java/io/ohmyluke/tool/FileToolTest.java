package io.ohmyluke.tool;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.ohmyluke.policy.PermissionGrantLedger;
import io.ohmyluke.policy.ToolPermission;
import io.ohmyluke.policy.ToolPermissionGrant;
import io.ohmyluke.policy.ToolPermissionPolicy;
import io.ohmyluke.policy.ToolPermissionRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

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
        Path npmCredentials = Files.writeString(project.resolve(".npmrc"), "_auth=opaque");
        FileTool tool = tool(project, true, List.of());

        FileToolResult policyMutation = tool.execute(FileToolRequest.write(
                "write-policy",
                permissions,
                "changed".getBytes(StandardCharsets.UTF_8)));
        FileToolResult secretRead = tool.execute(FileToolRequest.read("read-secret", secret));
        FileToolResult npmRead = tool.execute(FileToolRequest.read("read-npm-secret", npmCredentials));

        assertEquals(ToolPermission.DENY, policyMutation.permission().permission());
        assertEquals(ToolPermission.DENY, secretRead.permission().permission());
        assertEquals(ToolPermission.DENY, npmRead.permission().permission());
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

    @Test
    void rejectsOverlappingMoveBeforeCreatingACheckpoint() throws IOException {
        Path project = Files.createDirectory(temporaryDirectory.resolve("project"));
        Path source = Files.createDirectories(project.resolve("a/b"));
        Files.writeString(source.resolve("value.txt"), "value");
        FileTool tool = tool(project, false, List.of());

        FileToolResult result = tool.execute(FileToolRequest.move("move-overlap", source, project.resolve("a")));

        assertEquals(ToolPermission.DENY, result.permission().permission());
        assertEquals("file.overlapping-move", result.permission().reasonCode());
        assertEquals("value", Files.readString(source.resolve("value.txt")));
    }

    @Test
    void keepsTheFirstCheckpointOnIdempotentRetryAndRejectsIdReuse() throws IOException {
        Path project = Files.createDirectory(temporaryDirectory.resolve("project"));
        Path first = Files.writeString(project.resolve("first.txt"), "before");
        Path second = Files.writeString(project.resolve("second.txt"), "second");
        FileTool tool = tool(project, false, List.of());
        FileToolRequest request = FileToolRequest.write("same-op", first, "after".getBytes(StandardCharsets.UTF_8));

        assertTrue(tool.execute(request).executed());
        assertTrue(tool.execute(request).executed());
        FileToolResult conflicting = tool.execute(FileToolRequest.write(
                "same-op", second, "changed".getBytes(StandardCharsets.UTF_8)));
        tool.restore("same-op");

        assertFalse(conflicting.executed());
        assertEquals("before", Files.readString(first));
        assertEquals("second", Files.readString(second));
    }

    @Test
    void refusesATamperedCheckpointBeforeTouchingAnyPath() throws IOException {
        Path project = Files.createDirectory(temporaryDirectory.resolve("project"));
        Path source = Files.writeString(project.resolve("source.txt"), "before");
        Path victim = Files.writeString(temporaryDirectory.resolve("victim.txt"), "untouched");
        FileTool tool = tool(project, false, List.of());
        FileToolResult result = tool.execute(FileToolRequest.write(
                "tamper-1", source, "after".getBytes(StandardCharsets.UTF_8)));
        Path manifest = project.resolve(".oml/runs/run-001/file-checkpoints/tamper-1.json");
        Files.writeString(manifest, Files.readString(manifest).replace(source.toString(), victim.toString()));

        assertThrows(FileCheckpointException.class, () -> tool.restore(result.checkpointId()));
        assertEquals("untouched", Files.readString(victim));
        assertEquals("after", Files.readString(source));
    }

    @Test
    void rejectsOversizedCheckpointBeforeReadingTheFileIntoMemory() throws IOException {
        Path project = Files.createDirectory(temporaryDirectory.resolve("project"));
        Path large = project.resolve("large.bin");
        try (FileChannel channel = FileChannel.open(large, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            channel.position(33L * 1024 * 1024);
            channel.write(java.nio.ByteBuffer.wrap(new byte[] {1}));
        }
        FileTool tool = tool(project, false, List.of());

        FileToolResult result = tool.execute(FileToolRequest.write("large-1", large, new byte[] {2}));

        assertFalse(result.executed());
        assertEquals(33L * 1024 * 1024 + 1, Files.size(large));
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void restoresExecutablePermissions() throws IOException {
        Path project = Files.createDirectory(temporaryDirectory.resolve("project"));
        Path script = Files.writeString(project.resolve("script"), "before");
        Set<PosixFilePermission> original = Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE);
        Files.setPosixFilePermissions(script, original);
        FileTool tool = tool(project, false, List.of());

        FileToolResult result = tool.execute(FileToolRequest.write(
                "mode-1", script, "after".getBytes(StandardCharsets.UTF_8)));
        tool.restore(result.checkpointId());

        assertEquals(original, Files.getPosixFilePermissions(script));
        assertEquals("before", Files.readString(script));
    }

    @Test
    void rechecksRiskClassAfterApprovalBeforeDeleting() throws IOException {
        Path project = Files.createDirectory(temporaryDirectory.resolve("project"));
        Path target = Files.writeString(project.resolve("target"), "file");
        io.ohmyluke.policy.ToolPermissionEvaluator mutatingEvaluator = request -> {
            try {
                Files.delete(target);
                Files.createDirectory(target);
                Files.writeString(target.resolve("child.txt"), "keep");
            } catch (IOException error) {
                throw new RuntimeException(error);
            }
            return io.ohmyluke.policy.ToolPermissionDecision.allow("test.allow", "test", null);
        };
        FileTool tool = new FileTool(project, "run-001", mutatingEvaluator, CLOCK);

        FileToolResult result = tool.execute(FileToolRequest.delete("delete-race", target));

        assertEquals(ToolPermission.DENY, result.permission().permission());
        assertEquals("file.permission-scope-changed", result.permission().reasonCode());
        assertEquals("keep", Files.readString(target.resolve("child.txt")));
    }

    private static FileTool tool(
            Path project,
            boolean autonomous,
            List<ToolPermissionGrant> grants) {
        ToolPermissionPolicy permissions = new ToolPermissionPolicy(
                new PermissionGrantLedger(grants),
                project,
                autonomous,
                CLOCK);
        return new FileTool(project, "run-001", permissions, CLOCK);
    }
}
