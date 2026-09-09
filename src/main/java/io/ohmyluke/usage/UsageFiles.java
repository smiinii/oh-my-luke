package io.ohmyluke.usage;

import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;

/** Read-only, bounded access to the selected project's own records; never follows record links. */
public final class UsageFiles {
    private UsageFiles() {}
    public static boolean check(Path root, Path file, long limit) throws IOException {
        if (!file.normalize().startsWith(root) || !file.equals(file.normalize())) { throw new IOException("outside project"); }
        Path current = root;
        for (Path part : root.relativize(file)) {
            current = current.resolve(part);
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) { return false; }
            if (Files.isSymbolicLink(current)) { throw new IOException("linked record"); }
        }
        var attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (attributes.isRegularFile()) {
            if (attributes.size() > limit) { throw new IOException("record limit"); }
            if (file.getFileSystem().supportedFileAttributeViews().contains("unix")
                    && ((Number) Files.getAttribute(file, "unix:nlink", LinkOption.NOFOLLOW_LINKS)).longValue() != 1) {
                throw new IOException("hard linked record");
            }
        } else if (!attributes.isDirectory()) { throw new IOException("not a record"); }
        return true;
    }
    public static byte[] read(Path root, Path file, int limit) throws IOException {
        if (!check(root, file, limit) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) { throw new IOException("missing record"); }
        try (var channel = FileChannel.open(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = Channels.newInputStream(channel).readNBytes(limit + 1);
            if (bytes.length > limit) { throw new IOException("record limit"); }
            return bytes;
        }
    }
}
