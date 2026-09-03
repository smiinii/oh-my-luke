package io.ohmyluke.preset;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;

/** Protects operator-owned contracts and validators from filesystem aliases of edit targets. */
final class OperatorFileGuard {
    private OperatorFileGuard() {}

    static boolean sameFile(Path first, Path second) {
        Path left = first.toAbsolutePath().normalize();
        Path right = second.toAbsolutePath().normalize();
        if (left.equals(right)) { return true; }
        try {
            return Files.isSameFile(left, right);
        } catch (NoSuchFileException missing) {
            // Preserve existing validation timing; the file/process tool still rejects missing inputs.
            return false;
        } catch (IOException | SecurityException error) {
            throw new IllegalArgumentException("cannot verify operator-owned file identity", error);
        }
    }
}
