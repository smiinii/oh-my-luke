package io.ohmyluke.profile;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectLocatorTest {
    @TempDir Path directory;

    @Test void subdirectoriesUseNearestRepositoryAndWorktreesStaySeparate() throws Exception {
        Path home = Files.createDirectory(directory.resolve("home"));
        Path repo = Files.createDirectories(home.resolve("repo/.git")).getParent();
        Path child = Files.createDirectories(repo.resolve("src/deep"));
        assertEquals(repo.toRealPath(), ProjectLocator.locate(child, home, null));
        Path nested = Files.createDirectories(child.resolve("nested/.git")).getParent();
        assertEquals(nested.toRealPath(), ProjectLocator.locate(nested, home, null));
        Path worktree = Files.createDirectories(home.resolve("worktree"));
        Files.writeString(worktree.resolve(".git"), "gitdir: " + repo.resolve(".git/worktrees/test") + "\n");
        assertEquals(worktree.toRealPath(), ProjectLocator.locate(worktree, home, null));
    }

    @Test void legacyNonGitRunsRemainDiscoverableAndNamesDoNotDefineIdentity() throws Exception {
        Path home = Files.createDirectory(directory.resolve("home"));
        Path a = Files.createDirectories(home.resolve("a/same/.oml/runs")).getParent().getParent();
        Path b = Files.createDirectories(home.resolve("b/same"));
        Path child = Files.createDirectories(a.resolve("src"));
        assertEquals(a.toRealPath(), ProjectLocator.locate(child, home, null));
        assertEquals(b.toRealPath(), ProjectLocator.locate(b, home, null));
        assertNotEquals(ProjectLocator.locate(child, home, null), ProjectLocator.locate(b, home, null));
    }

    @Test void ambiguousFoldersRequireAnExplicitWorkFolderAndNeverUseHomeAsProject() throws Exception {
        Path home = Files.createDirectory(directory.resolve("home"));
        Path desktop = Files.createDirectory(home.resolve("Desktop"));
        Files.createDirectory(home.resolve(".git"));
        assertThrows(IllegalArgumentException.class, () -> ProjectLocator.locate(home, home, null));
        assertThrows(IllegalArgumentException.class, () -> ProjectLocator.locate(desktop, home, null));
        Path work = Files.createDirectory(desktop.resolve("work"));
        assertEquals(work.toRealPath(), ProjectLocator.locate(desktop, home, work));
        assertThrows(IllegalArgumentException.class, () -> ProjectLocator.locate(work, home, home));
    }

    @Test void explicitRootOverridesAutoDetectionWithoutCreatingFolders() throws Exception {
        Path home = Files.createDirectory(directory.resolve("home"));
        Path repo = Files.createDirectories(home.resolve("repo/.git")).getParent();
        Path child = Files.createDirectory(repo.resolve("child"));
        assertEquals(child.toRealPath(), ProjectLocator.locate(repo, home, child));
        assertThrows(IllegalArgumentException.class, () -> ProjectLocator.locate(repo, home, repo.resolve("missing")));
    }

    @Test void isolatedHomeWorksEvenWhenTheOperatingSystemHomeDoesNotExist() throws Exception {
        Path isolated = Files.createDirectory(directory.resolve("isolated-home"));
        Path project = Files.createDirectory(directory.resolve("work"));
        Path absentOsHome = directory.resolve("nonexistent-passwd-home");
        assertEquals(project.toRealPath(), ProjectLocator.locate(project, absentOsHome, null, isolated));
        assertThrows(IllegalArgumentException.class, () -> ProjectLocator.locate(isolated, absentOsHome, null, isolated));
        assertFalse(Files.exists(absentOsHome));
    }
}
