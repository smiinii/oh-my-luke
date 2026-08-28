package io.ohmyluke.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectBoundaryPolicyTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void allowsOrdinaryProjectPaths() throws IOException {
        Path project = Files.createDirectory(temporaryDirectory.resolve("project"));
        Path source = Files.createDirectories(project.resolve("src/main"));

        ProjectBoundaryPolicy policy = new ProjectBoundaryPolicy(project);

        assertEquals(PolicyOutcome.CONTINUE, policy.evaluate(source.resolve("App.java"), FileAccess.WRITE).outcome());
        assertEquals(PolicyOutcome.CONTINUE, policy.evaluate(Path.of("src/main/App.java"), FileAccess.READ).outcome());
    }

    @Test
    void blocksLexicalEscapeFromProject() throws IOException {
        Path project = Files.createDirectory(temporaryDirectory.resolve("project"));
        ProjectBoundaryPolicy policy = new ProjectBoundaryPolicy(project);

        PolicyDecision decision = policy.evaluate(project.resolve("../outside.txt"), FileAccess.WRITE);

        assertEquals(PolicyOutcome.BLOCKED, decision.outcome());
        assertEquals("boundary.parent-traversal", decision.reasonCode());
        assertFalse(decision.resumable());
    }

    @Test
    void blocksAnExistingSymlinkThatEscapesTheProject() throws IOException {
        Path project = Files.createDirectory(temporaryDirectory.resolve("project"));
        Path outside = Files.createDirectory(temporaryDirectory.resolve("outside"));
        Path link = Files.createSymbolicLink(project.resolve("escape"), outside);
        ProjectBoundaryPolicy policy = new ProjectBoundaryPolicy(project);

        PolicyDecision decision = policy.evaluate(link.resolve("secret.txt"), FileAccess.READ);

        assertEquals(PolicyOutcome.BLOCKED, decision.outcome());
        assertEquals("boundary.symlink-escape", decision.reasonCode());
    }

    @Test
    void refusesToWriteOrDeleteThroughTheFinalSymlink() throws IOException {
        Path project = Files.createDirectory(temporaryDirectory.resolve("project"));
        Path target = Files.writeString(project.resolve("target.txt"), "safe");
        Path link = Files.createSymbolicLink(project.resolve("link.txt"), target.getFileName());
        ProjectBoundaryPolicy policy = new ProjectBoundaryPolicy(project);

        PolicyDecision write = policy.evaluate(link, FileAccess.WRITE);
        PolicyDecision delete = policy.evaluate(link, FileAccess.DELETE);
        PolicyDecision read = policy.evaluate(link, FileAccess.READ);

        assertEquals("boundary.final-symlink", write.reasonCode());
        assertEquals("boundary.final-symlink", delete.reasonCode());
        assertEquals("boundary.final-symlink", read.reasonCode());
    }

    @Test
    void rawParentTraversalCannotHideBehindAnInternalSymlink() throws IOException {
        Path project = Files.createDirectory(temporaryDirectory.resolve("project"));
        Path self = Files.createSymbolicLink(project.resolve("self"), Path.of("."));
        ProjectBoundaryPolicy policy = new ProjectBoundaryPolicy(project);

        PolicyDecision decision = policy.evaluate(self.resolve("../outside.txt"), FileAccess.WRITE);

        assertEquals(PolicyOutcome.BLOCKED, decision.outcome());
        assertEquals("boundary.parent-traversal", decision.reasonCode());
    }

    @Test
    void deleteRequiresApprovalBoundToTheExactPathAndOperation() throws IOException {
        Path project = Files.createDirectory(temporaryDirectory.resolve("project"));
        Path target = Files.writeString(project.resolve("target.txt"), "safe");
        Instant now = Instant.parse("2026-08-28T00:00:00Z");
        ProjectBoundaryPolicy policy = new ProjectBoundaryPolicy(
                project,
                "run-001",
                Clock.fixed(now, ZoneOffset.UTC));
        FileApprovalGrant wrong = new FileApprovalGrant(
                "approval-1",
                "run-001",
                policy.projectRoot().toString(),
                "other.txt",
                FileAccess.DELETE,
                now.plusSeconds(30).toEpochMilli());
        FileApprovalGrant exact = new FileApprovalGrant(
                "approval-2",
                "run-001",
                policy.projectRoot().toString(),
                "target.txt",
                FileAccess.DELETE,
                now.plusSeconds(30).toEpochMilli());
        FileApprovalGrant expired = new FileApprovalGrant(
                "approval-3",
                "run-001",
                policy.projectRoot().toString(),
                "target.txt",
                FileAccess.DELETE,
                now.toEpochMilli());

        assertEquals("boundary.approval-required", policy.evaluate(target, FileAccess.DELETE).reasonCode());
        assertEquals("boundary.approval-required", policy.evaluate(target, FileAccess.DELETE, wrong).reasonCode());
        assertEquals("boundary.approval-required", policy.evaluate(target, FileAccess.DELETE, expired).reasonCode());
        assertEquals(PolicyOutcome.CONTINUE, policy.evaluate(target, FileAccess.DELETE, exact).outcome());
    }
}
