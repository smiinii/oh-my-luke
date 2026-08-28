package io.ohmyluke.tool;

import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.NativeLong;
import com.sun.jna.Platform;
import com.sun.jna.Pointer;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** Descriptor-relative POSIX operations that cannot follow a swapped symbolic-link component. */
final class SecureFileOperations {
    private static final Posix POSIX = Platform.isWindows() ? null : Native.load(Platform.C_LIBRARY_NAME, Posix.class);
    private static final int BUFFER_BYTES = 16 * 1024;
    private static final int O_RDONLY = 0;
    private static final int O_WRONLY = 1;
    private static final int O_CREAT = Platform.isMac() ? 0x0200 : 0x0040;
    private static final int O_EXCL = Platform.isMac() ? 0x0800 : 0x0080;
    private static final int O_DIRECTORY = Platform.isMac() ? 0x100000 : 0x10000;
    private static final int O_NOFOLLOW = Platform.isMac() ? 0x0100 : 0x20000;
    private static final int O_CLOEXEC = Platform.isMac() ? 0x01000000 : 0x80000;
    private static final int AT_REMOVEDIR = Platform.isMac() ? 0x0080 : 0x0200;
    private static final int ENOENT = 2;

    private SecureFileOperations() {}

    static byte[] readAllBytes(Path target, long maximumBytes) {
        requirePosix();
        try (Descriptor descriptor = openTarget(target, O_RDONLY | O_NOFOLLOW | O_CLOEXEC)) {
            java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
            Memory buffer = new Memory(BUFFER_BYTES);
            long total = 0;
            while (true) {
                long count = POSIX.read(descriptor.value(), buffer, new NativeLong(BUFFER_BYTES)).longValue();
                if (count == 0) {
                    return output.toByteArray();
                }
                if (count < 0) {
                    throw nativeFailure("secure descriptor-relative read failed", target);
                }
                total += count;
                if (total > maximumBytes) {
                    throw new FileCheckpointException("secure read exceeds its byte limit: " + target);
                }
                output.writeBytes(buffer.getByteArray(0, (int) count));
            }
        }
    }

    static void writeFile(Path target, byte[] content) {
        requirePosix();
        try (Descriptor parent = openParent(target)) {
            String temporary = ".oml-write-" + UUID.randomUUID();
            int raw = POSIX.openat(
                    parent.value(), temporary, O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, 0600);
            if (raw < 0) {
                throw nativeFailure("failed to create a secure temporary file", target);
            }
            boolean renamed = false;
            try (Descriptor descriptor = new Descriptor(raw)) {
                if (POSIX.fchmod(descriptor.value(), 0600) != 0) {
                    throw nativeFailure("failed to restrict a secure temporary file", target);
                }
                Memory buffer = new Memory(Math.max(1, content.length));
                if (content.length > 0) {
                    buffer.write(0, content, 0, content.length);
                }
                long offset = 0;
                while (offset < content.length) {
                    long count = POSIX.write(
                                    descriptor.value(), buffer.share(offset), new NativeLong(content.length - offset))
                            .longValue();
                    if (count <= 0) {
                        throw nativeFailure("secure descriptor-relative write failed", target);
                    }
                    offset += count;
                }
                if (POSIX.fsync(descriptor.value()) != 0) {
                    throw nativeFailure("failed to sync a secure file replacement", target);
                }
                if (POSIX.renameat(
                                parent.value(), temporary, parent.value(), target.getFileName().toString())
                        != 0) {
                    throw nativeFailure("secure descriptor-relative file replacement failed", target);
                }
                renamed = true;
            } finally {
                if (!renamed) {
                    POSIX.unlinkat(parent.value(), temporary, 0);
                }
            }
        }
    }

    static void createDirectory(Path target) {
        requirePosix();
        try (Descriptor parent = openParent(target)) {
            if (POSIX.mkdirat(parent.value(), target.getFileName().toString(), 0777) != 0) {
                throw nativeFailure("secure descriptor-relative directory creation failed", target);
            }
        }
    }

    static void move(Path source, Path destination) {
        requirePosix();
        try (Descriptor sourceParent = openParent(source);
                Descriptor destinationParent = openParent(destination)) {
            if (POSIX.renameat(
                            sourceParent.value(), source.getFileName().toString(),
                            destinationParent.value(), destination.getFileName().toString())
                    != 0) {
                throw nativeFailure("secure descriptor-relative move failed", source);
            }
        }
    }

    static void deleteTree(Path target) {
        requirePosix();
        if (Files.notExists(target, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        List<Path> entries;
        try (var walked = Files.walk(target)) {
            entries = walked.sorted(Comparator.comparingInt(Path::getNameCount).reversed()).toList();
        } catch (java.io.IOException error) {
            throw new FileCheckpointException("failed to enumerate a path for secure deletion: " + target, error);
        }
        for (Path entry : entries) {
            unlink(entry, Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS));
        }
    }

    private static void unlink(Path target, boolean directory) {
        try (Descriptor parent = openParent(target)) {
            if (POSIX.unlinkat(parent.value(), target.getFileName().toString(), directory ? AT_REMOVEDIR : 0) != 0) {
                int error = Native.getLastError();
                if (error != ENOENT) {
                    throw new FileCheckpointException(
                            "secure descriptor-relative delete failed (errno " + error + "): " + target);
                }
            }
        }
    }

    private static Descriptor openTarget(Path target, int flags) {
        try (Descriptor parent = openParent(target)) {
            int descriptor = POSIX.openat(parent.value(), target.getFileName().toString(), flags);
            if (descriptor < 0) {
                throw nativeFailure("failed to open target without following symbolic links", target);
            }
            return new Descriptor(descriptor);
        }
    }

    private static Descriptor openParent(Path target) {
        requirePosix();
        Path absolute = target.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        if (!absolute.isAbsolute() || parent == null || absolute.getFileName() == null) {
            throw new FileCheckpointException("secure filesystem target must have an absolute parent: " + target);
        }
        int current = POSIX.open(absolute.getRoot().toString(), O_RDONLY | O_DIRECTORY | O_CLOEXEC);
        if (current < 0) {
            throw nativeFailure("failed to open filesystem root securely", target);
        }
        try {
            for (Path component : parent) {
                int next = POSIX.openat(
                        current, component.toString(), O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
                if (next < 0) {
                    throw nativeFailure("failed to open a parent without following symbolic links", target);
                }
                POSIX.close(current);
                current = next;
            }
            Descriptor result = new Descriptor(current);
            current = -1;
            return result;
        } finally {
            if (current >= 0) {
                POSIX.close(current);
            }
        }
    }

    private static void requirePosix() {
        if (POSIX == null || (!Platform.isMac() && !Platform.isLinux())) {
            throw new FileCheckpointException(
                    "secure descriptor-relative file operations are unavailable on this operating system");
        }
    }

    private static FileCheckpointException nativeFailure(String detail, Path target) {
        return new FileCheckpointException(detail + " (errno " + Native.getLastError() + "): " + target);
    }

    private record Descriptor(int value) implements AutoCloseable {
        @Override
        public void close() {
            POSIX.close(value);
        }
    }

    private interface Posix extends Library {
        int open(String path, int flags);
        int openat(int directory, String path, int flags);
        int openat(int directory, String path, int flags, int mode);
        NativeLong read(int descriptor, Pointer buffer, NativeLong count);
        NativeLong write(int descriptor, Pointer buffer, NativeLong count);
        int fsync(int descriptor);
        int fchmod(int descriptor, int mode);
        int renameat(int sourceDirectory, String source, int destinationDirectory, String destination);
        int mkdirat(int directory, String path, int mode);
        int unlinkat(int directory, String path, int flags);
        int close(int descriptor);
    }
}
