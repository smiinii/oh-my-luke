package io.ohmyluke.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@EnabledOnOs({OS.LINUX, OS.MAC})
class SecureFileOperationsTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void rejectsAParentSwappedToASymlinkBeforeWrite() throws IOException {
        SwappedParent swapped = swappedParent("write");
        Path original = Files.writeString(swapped.relocated().resolve("target.txt"), "original");
        Path outside = Files.writeString(swapped.outside().resolve("target.txt"), "outside");

        assertThrows(
                FileCheckpointException.class,
                () -> SecureFileOperations.writeFile(
                        swapped.requested().resolve("target.txt"), "changed".getBytes(StandardCharsets.UTF_8)));

        assertEquals("original", Files.readString(original));
        assertEquals("outside", Files.readString(outside));
    }

    @Test
    void rejectsAParentSwappedToASymlinkBeforeDirectoryCreation() throws IOException {
        SwappedParent swapped = swappedParent("mkdir");

        assertThrows(
                FileCheckpointException.class,
                () -> SecureFileOperations.createDirectory(swapped.requested().resolve("created")));

        assertFalse(Files.exists(swapped.relocated().resolve("created")));
        assertFalse(Files.exists(swapped.outside().resolve("created")));
    }

    @Test
    void rejectsASourceParentSwappedToASymlinkBeforeMove() throws IOException {
        SwappedParent swapped = swappedParent("move");
        Path destinationParent = Files.createDirectory(temporaryDirectory.resolve("move-destination")).toRealPath();
        Path original = Files.writeString(swapped.relocated().resolve("source.txt"), "original");
        Path outside = Files.writeString(swapped.outside().resolve("source.txt"), "outside");

        assertThrows(
                FileCheckpointException.class,
                () -> SecureFileOperations.move(
                        swapped.requested().resolve("source.txt"), destinationParent.resolve("moved.txt")));

        assertEquals("original", Files.readString(original));
        assertEquals("outside", Files.readString(outside));
        assertFalse(Files.exists(destinationParent.resolve("moved.txt")));
    }

    @Test
    void rejectsAParentSwappedToASymlinkBeforeDelete() throws IOException {
        SwappedParent swapped = swappedParent("delete");
        Path original = Files.writeString(swapped.relocated().resolve("target.txt"), "original");
        Path outside = Files.writeString(swapped.outside().resolve("target.txt"), "outside");

        assertThrows(
                FileCheckpointException.class,
                () -> SecureFileOperations.deleteTree(swapped.requested().resolve("target.txt")));

        assertEquals("original", Files.readString(original));
        assertEquals("outside", Files.readString(outside));
    }

    @Test
    void rejectsAFifoWithoutBlocking() throws Exception {
        Path realTemporary = temporaryDirectory.toRealPath();
        Path fifo = realTemporary.resolve("input.fifo");
        Process mkfifo = new ProcessBuilder("/usr/bin/mkfifo", fifo.toString()).start();
        assertEquals(0, mkfifo.waitFor());

        assertTimeoutPreemptively(
                Duration.ofSeconds(1),
                () -> assertThrows(
                        FileCheckpointException.class,
                        () -> SecureFileOperations.readAllBytes(fifo, 1024)));
    }

    @Test
    void preservesExecutablePermissionsWhenReplacingARegularFile() throws IOException {
        Path realTemporary = temporaryDirectory.toRealPath();
        Path executable = Files.writeString(realTemporary.resolve("script.sh"), "before");
        Files.setPosixFilePermissions(executable, Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE));

        SecureFileOperations.writeFile(executable, "after".getBytes(StandardCharsets.UTF_8));

        assertEquals(
                Set.of(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE),
                Files.getPosixFilePermissions(executable));
    }

    private SwappedParent swappedParent(String prefix) throws IOException {
        Path realTemporary = temporaryDirectory.toRealPath();
        Path requested = Files.createDirectory(realTemporary.resolve(prefix + "-parent"));
        Path relocated = realTemporary.resolve(prefix + "-relocated");
        Path outside = Files.createDirectory(realTemporary.resolve(prefix + "-outside"));
        Files.move(requested, relocated);
        Files.createSymbolicLink(requested, outside);
        return new SwappedParent(requested, relocated, outside);
    }

    private record SwappedParent(Path requested, Path relocated, Path outside) {}
}
