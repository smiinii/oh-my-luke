package io.ohmyluke.preset;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OperatorFileGuardTest {
    @TempDir Path project;

    @Test void lexicalIdentityIsRejectedEvenBeforeTheFileExists() {
        assertTrue(OperatorFileGuard.sameFile(project.resolve("input.txt"), project.resolve("child/../input.txt")));
        assertFalse(OperatorFileGuard.sameFile(project.resolve("input.txt"), project.resolve("other.txt")));
    }

    @Test void physicalAliasesAreProtectedButDistinctFilesWithEqualBytesAreNot() throws Exception {
        Path original = Files.writeString(project.resolve("input.txt"), "operator owned");
        Path distinct = Files.writeString(project.resolve("distinct.txt"), "operator owned");
        Path symbolic = Files.createSymbolicLink(project.resolve("symbolic.txt"), original);
        Path hard = Files.createLink(project.resolve("hard.txt"), original);
        assertTrue(OperatorFileGuard.sameFile(original, symbolic));
        assertTrue(OperatorFileGuard.sameFile(original, hard));
        assertFalse(OperatorFileGuard.sameFile(original, distinct));
        assertFalse(OperatorFileGuard.sameFile(original, project.resolve("missing.txt")));
    }
}
