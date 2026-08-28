package io.ohmyluke.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
        assertEquals("boundary.outside", decision.reasonCode());
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
        assertEquals(PolicyOutcome.CONTINUE, read.outcome());
        assertTrue(read.resumable());
    }
}
