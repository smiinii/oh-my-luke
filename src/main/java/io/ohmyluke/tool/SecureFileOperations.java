package io.ohmyluke.tool;

import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.NativeLong;
import com.sun.jna.Platform;
import com.sun.jna.Pointer;
import java.nio.file.Path;
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
    private static final int O_NONBLOCK = Platform.isMac() ? 0x0004 : 0x0800;
    private static final int AT_REMOVEDIR = Platform.isMac() ? 0x0080 : 0x0200;
    private static final int ENOENT = 2;
    private static final int ENOTDIR = 20;
    private static final int ELOOP = Platform.isMac() ? 62 : 40;
    private static final int S_IFMT = 0170000;
    private static final int S_IFREG = 0100000;
    private static final int MAX_DELETE_ENTRIES = 10_000;

    private SecureFileOperations() {}

    static byte[] readAllBytes(Path target, long maximumBytes) {
        requirePosix();
        try (Descriptor descriptor = openTarget(target, O_RDONLY | O_NONBLOCK | O_NOFOLLOW | O_CLOEXEC)) {
            requireRegularFile(descriptor, target);
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
            int mode = existingFileMode(parent, target);
            String temporary = ".oml-write-" + UUID.randomUUID();
            int raw = POSIX.openat(
                    parent.value(), temporary, O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, 0600);
            if (raw < 0) {
                throw nativeFailure("failed to create a secure temporary file", target);
            }
            boolean renamed = false;
            try (Descriptor descriptor = new Descriptor(raw)) {
                if (POSIX.fchmod(descriptor.value(), mode) != 0) {
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
        try (Descriptor parent = openParent(target)) {
            DeleteBudget budget = new DeleteBudget();
            deleteEntry(parent, target.getFileName().toString(), target, budget);
        }
    }

    private static void deleteEntry(Descriptor parent, String name, Path display, DeleteBudget budget) {
        budget.consume(display);
        int raw = POSIX.openat(
                parent.value(), name, O_RDONLY | O_DIRECTORY | O_NONBLOCK | O_NOFOLLOW | O_CLOEXEC);
        if (raw >= 0) {
            try (Descriptor directory = new Descriptor(raw)) {
                deleteDirectoryContents(directory, display, budget);
            }
            if (POSIX.unlinkat(parent.value(), name, AT_REMOVEDIR) != 0 && Native.getLastError() != ENOENT) {
                throw nativeFailure("secure descriptor-relative directory delete failed", display);
            }
            return;
        }
        int openError = Native.getLastError();
        if (openError == ENOENT) {
            return;
        }
        if (openError != ENOTDIR && openError != ELOOP) {
            throw new FileCheckpointException(
                    "failed to open an entry for secure deletion (errno " + openError + "): " + display);
        }
        if (POSIX.unlinkat(parent.value(), name, 0) != 0 && Native.getLastError() != ENOENT) {
            throw nativeFailure("secure descriptor-relative file delete failed", display);
        }
    }

    private static void deleteDirectoryContents(Descriptor directory, Path display, DeleteBudget budget) {
        int duplicate = POSIX.dup(directory.value());
        if (duplicate < 0) {
            throw nativeFailure("failed to duplicate a directory descriptor", display);
        }
        Pointer stream = POSIX.fdopendir(duplicate);
        if (stream == null) {
            POSIX.close(duplicate);
            throw nativeFailure("failed to open a secure directory stream", display);
        }
        try {
            while (true) {
                Native.setLastError(0);
                Pointer entry = POSIX.readdir(stream);
                if (entry == null) {
                    int error = Native.getLastError();
                    if (error != 0) {
                        throw new FileCheckpointException(
                                "secure directory enumeration failed (errno " + error + "): " + display);
                    }
                    return;
                }
                String name = directoryEntryName(entry, display);
                if (!name.equals(".") && !name.equals("..")) {
                    deleteEntry(directory, name, display.resolve(name), budget);
                }
            }
        } finally {
            POSIX.closedir(stream);
        }
    }

    private static String directoryEntryName(Pointer entry, Path display) {
        int offset = Platform.isMac() ? 21 : 19;
        int length = Platform.isMac()
                ? Short.toUnsignedInt(entry.getShort(18))
                : Math.max(0, Short.toUnsignedInt(entry.getShort(16)) - offset);
        if (length <= 0 || length > 1024) {
            throw new FileCheckpointException("invalid native directory entry while deleting: " + display);
        }
        byte[] raw = entry.getByteArray(offset, length);
        int end = 0;
        while (end < raw.length && raw[end] != 0) {
            end++;
        }
        String name = new String(raw, 0, end, java.nio.charset.StandardCharsets.UTF_8);
        if (name.isEmpty() || name.indexOf('/') >= 0 || name.indexOf('\0') >= 0) {
            throw new FileCheckpointException("invalid native directory entry while deleting: " + display);
        }
        return name;
    }

    private static int existingFileMode(Descriptor parent, Path target) {
        int raw = POSIX.openat(
                parent.value(),
                target.getFileName().toString(),
                O_RDONLY | O_NONBLOCK | O_NOFOLLOW | O_CLOEXEC);
        if (raw < 0) {
            if (Native.getLastError() == ENOENT) {
                return 0600;
            }
            throw nativeFailure("failed to inspect an existing file securely", target);
        }
        try (Descriptor existing = new Descriptor(raw)) {
            return requireRegularFile(existing, target) & 07777;
        }
    }

    private static int requireRegularFile(Descriptor descriptor, Path target) {
        Memory status = new Memory(512);
        status.clear();
        if (POSIX.fstat(descriptor.value(), status) != 0) {
            throw nativeFailure("failed to inspect an opened file securely", target);
        }
        int mode = Platform.isMac()
                ? Short.toUnsignedInt(status.getShort(4))
                : status.getInt(Platform.isARM() ? 16 : 24);
        if ((mode & S_IFMT) != S_IFREG) {
            throw new FileCheckpointException("secure file operation requires a regular file: " + target);
        }
        return mode;
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

    private static final class DeleteBudget {
        private int entries;

        void consume(Path path) {
            entries++;
            if (entries > MAX_DELETE_ENTRIES) {
                throw new FileCheckpointException(
                        "secure recursive delete exceeds " + MAX_DELETE_ENTRIES + " entries: " + path);
            }
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
        int fstat(int descriptor, Pointer status);
        int dup(int descriptor);
        Pointer fdopendir(int descriptor);
        Pointer readdir(Pointer directory);
        int closedir(Pointer directory);
        int renameat(int sourceDirectory, String source, int destinationDirectory, String destination);
        int mkdirat(int directory, String path, int mode);
        int unlinkat(int directory, String path, int flags);
        int close(int descriptor);
    }
}
