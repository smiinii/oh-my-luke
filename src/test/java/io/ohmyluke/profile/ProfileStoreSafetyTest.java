package io.ohmyluke.profile;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@EnabledOnOs({OS.MAC, OS.LINUX})
class ProfileStoreSafetyTest {
    @TempDir Path directory;

    @Test void rejectsSymbolicAndHardLinkedSettingsWithoutTouchingTheirTargets() throws Exception {
        Path home = Files.createDirectory(directory.resolve("home"));
        Path settings = Files.createDirectory(home.resolve(".oml")).resolve("settings.json");
        Path outside = Files.writeString(directory.resolve("outside"), "private-unrelated-file");
        var profiles = new ExecutionProfiles(home, null);
        Files.createSymbolicLink(settings, outside);
        assertThrows(IllegalStateException.class, profiles::resolve);
        assertThrows(IllegalStateException.class, profiles::setup);
        assertThrows(IllegalStateException.class, () -> profiles.saveGlobal(ExecutionProfile.defaults()));
        Files.delete(settings);
        Files.createLink(settings, outside);
        assertThrows(IllegalStateException.class, profiles::resolve);
        assertThrows(IllegalStateException.class, profiles::setup);
        assertEquals("private-unrelated-file", Files.readString(outside));
    }

    @Test void rejectsLinkedDirectoriesLocksAndRunRoots() throws Exception {
        Path home = Files.createDirectory(directory.resolve("home"));
        Path outside = Files.createDirectory(directory.resolve("outside"));
        Files.createSymbolicLink(home.resolve(".oml"), outside);
        var profiles = new ExecutionProfiles(home, null);
        assertThrows(IllegalStateException.class, profiles::setup);
        assertThrows(IllegalStateException.class, profiles::resolve);
        Files.delete(home.resolve(".oml"));
        Files.createDirectory(home.resolve(".oml"));
        Path target = Files.writeString(outside.resolve("file"), "preserved");
        Files.createSymbolicLink(home.resolve(".oml/settings.json.lock"), target);
        assertThrows(IllegalStateException.class, profiles::setup);
        assertEquals("preserved", Files.readString(target));
        Path project = Files.createDirectory(directory.resolve("project"));
        Files.createDirectory(project.resolve(".git"));
        Files.createDirectory(project.resolve(".oml"));
        Files.createSymbolicLink(project.resolve(".oml/runs"), outside);
        assertThrows(IllegalStateException.class, () -> ProjectLocator.locate(project, home, null));
        assertThrows(IllegalStateException.class, () -> ProjectLocator.locate(home, home, project));
    }

    @Test void privateSettingsAndLockFailurePreserveLastGoodValue() throws Exception {
        Path home = Files.createDirectory(directory.resolve("home"));
        var profiles = new ExecutionProfiles(home, null);
        profiles.setup();
        Path settings = home.resolve(".oml/settings.json");
        String original = Files.readString(settings);
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(settings));
        try (var channel = FileChannel.open(home.resolve(".oml/settings.json.lock"), StandardOpenOption.WRITE);
             var ignored = channel.lock()) {
            assertThrows(IllegalStateException.class, () -> profiles.saveGlobal(new ExecutionProfile("codex", "oml", "other")));
        }
        assertEquals(original, Files.readString(settings));
    }

    @Test void symbolicAliasesResolveToTheSameWorkFolder() throws Exception {
        Path home = Files.createDirectory(directory.resolve("home"));
        Path project = Files.createDirectory(directory.resolve("project"));
        Path alias = Files.createSymbolicLink(directory.resolve("alias"), project);
        assertEquals(ProjectLocator.locate(project, home, null), ProjectLocator.locate(alias, home, null));
    }
}
