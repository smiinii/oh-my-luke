package io.ohmyluke.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ohmyluke.policy.PermissionChoice;
import io.ohmyluke.policy.PermissionMessages;
import io.ohmyluke.policy.ToolCapability;
import io.ohmyluke.policy.ToolPermission;
import io.ohmyluke.policy.ToolPermissionRequest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectPermissionManagerTest {
    private static final Instant NOW = Instant.parse("2026-08-28T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @TempDir
    Path project;

    @Test
    void persistsProjectApprovalAcrossRunsAndProcesses() throws IOException {
        ProjectPermissionStore store = new ProjectPermissionStore(project);
        ProjectPermissionManager first = manager(store);
        ToolPermissionRequest original = request("push-1", "run-001", ToolCapability.EXTERNAL_WRITE, "git:origin");

        first.approve(original, PermissionChoice.PROJECT, NOW.plusSeconds(60).toEpochMilli());
        ProjectPermissionManager restarted = manager(store);
        ToolPermissionRequest nextRun = request("push-2", "run-002", ToolCapability.EXTERNAL_WRITE, "git:origin");

        assertEquals(ToolPermission.ALLOW, restarted.evaluate(nextRun).permission());
        assertTrue(Files.exists(project.resolve(".oml/permissions.json")));
        assertFalse(Files.readString(project.resolve(".oml/permissions.json")).contains("secret"));
    }

    @Test
    void consumesOneTimeApprovalDurably() {
        ProjectPermissionStore store = new ProjectPermissionStore(project);
        ProjectPermissionManager first = manager(store);
        ToolPermissionRequest request = request("push-1", "run-001", ToolCapability.EXTERNAL_WRITE, "git:origin");
        first.approve(request, PermissionChoice.ONCE, NOW.plusSeconds(60).toEpochMilli());

        assertEquals(ToolPermission.ALLOW, first.evaluate(request).permission());
        ProjectPermissionManager restarted = manager(store);

        assertEquals(ToolPermission.ASK, restarted.evaluate(request).permission());
    }

    @Test
    void consumesOneTimeApprovalOnlyOnceAcrossLiveManagers() {
        ProjectPermissionStore store = new ProjectPermissionStore(project);
        ProjectPermissionManager first = manager(store);
        ProjectPermissionManager second = new ProjectPermissionManager(store, CLOCK, () -> "second-grant");
        ToolPermissionRequest request = request("push-1", "run-001", ToolCapability.EXTERNAL_WRITE, "git:origin");
        first.approve(request, PermissionChoice.ONCE, NOW.plusSeconds(60).toEpochMilli());

        assertEquals(ToolPermission.ALLOW, second.evaluate(request).permission());
        assertEquals(ToolPermission.ASK, first.evaluate(request).permission());
    }

    @Test
    void preservesApprovalsWrittenByAnotherLiveManager() {
        ProjectPermissionStore store = new ProjectPermissionStore(project);
        ProjectPermissionManager first = manager(store);
        ProjectPermissionManager second = new ProjectPermissionManager(store, CLOCK, () -> "second-grant");
        ToolPermissionRequest git = request("push-1", "run-001", ToolCapability.EXTERNAL_WRITE, "git:origin");
        ToolPermissionRequest network = request("network-1", "run-001", ToolCapability.NETWORK_ACCESS, "network:any");

        first.approve(git, PermissionChoice.PROJECT, NOW.plusSeconds(60).toEpochMilli());
        second.approve(network, PermissionChoice.PROJECT, NOW.plusSeconds(60).toEpochMilli());

        assertEquals(ToolPermission.ALLOW, first.evaluate(git).permission());
        assertEquals(ToolPermission.ALLOW, first.evaluate(network).permission());
    }

    @Test
    void persistsAutonomyButNeverOverridesAnInvariant() {
        ProjectPermissionStore store = new ProjectPermissionStore(project);
        ProjectPermissionManager first = manager(store);
        first.setAutonomousProject(true);
        ProjectPermissionManager restarted = manager(store);

        assertEquals(
                ToolPermission.ALLOW,
                restarted.evaluate(request(
                                "push-1",
                                "run-001",
                                ToolCapability.EXTERNAL_WRITE,
                                "git:origin"))
                        .permission());
        assertEquals(
                ToolPermission.DENY,
                restarted.evaluate(request(
                                "policy-1",
                                "run-001",
                                ToolCapability.POLICY_MUTATION,
                                ".oml/permissions.json"))
                        .permission());
        assertTrue(PermissionMessages.autonomousEnabled().contains("다시 승인이 필요하도록"));
    }

    @Test
    void resetRemovesAutonomyAndEveryRememberedGrant() {
        ProjectPermissionStore store = new ProjectPermissionStore(project);
        ProjectPermissionManager manager = manager(store);
        ToolPermissionRequest request = request("push-1", "run-001", ToolCapability.EXTERNAL_WRITE, "git:origin");
        manager.approve(request, PermissionChoice.PROJECT, NOW.plusSeconds(60).toEpochMilli());
        manager.setAutonomousProject(true);

        manager.reset();

        assertFalse(manager.settings().autonomousProject());
        assertTrue(manager.settings().grants().isEmpty());
        assertEquals(ToolPermission.ASK, manager.evaluate(request).permission());
    }

    @Test
    void refusesToApproveAnInvariantOrLoadAnotherProjectsGrant() throws IOException {
        ProjectPermissionStore store = new ProjectPermissionStore(project);
        ProjectPermissionManager manager = manager(store);
        ToolPermissionRequest invariant = request(
                "policy-1",
                "run-001",
                ToolCapability.POLICY_MUTATION,
                ".oml/permissions.json");

        assertThrows(
                IllegalArgumentException.class,
                () -> manager.approve(invariant, PermissionChoice.PROJECT, NOW.plusSeconds(60).toEpochMilli()));

        Path permissionFile = project.resolve(".oml/permissions.json");
        Files.createDirectories(permissionFile.getParent());
        Files.writeString(permissionFile, """
                {
                  "schemaVersion": 1,
                  "projectRoot": "/different/project",
                  "autonomousProject": false,
                  "grants": []
                }
                """);
        assertThrows(CheckpointException.class, store::load);
    }

    private ProjectPermissionManager manager(ProjectPermissionStore store) {
        AtomicInteger ids = new AtomicInteger();
        return new ProjectPermissionManager(store, CLOCK, () -> "grant-" + ids.incrementAndGet());
    }

    private ToolPermissionRequest request(
            String operationId,
            String runId,
            ToolCapability capability,
            String target) {
        return new ToolPermissionRequest(operationId, runId, project, capability, target);
    }
}
