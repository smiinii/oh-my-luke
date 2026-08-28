package io.ohmyluke.tool;

import java.nio.file.Files;
import java.nio.file.Path;

/** Separate-JVM fixture proving that the durable mutation lock coordinates independent OML processes. */
public final class FileToolProcessFixture {
    private FileToolProcessFixture() {}

    public static void main(String[] arguments) throws Exception {
        Path project = Path.of(arguments[0]);
        boolean hold = arguments[1].equals("hold");
        Path started = Path.of(arguments[2]);
        Path entered = Path.of(arguments[3]);
        Path release = Path.of(arguments[4]);
        Files.writeString(started, "started");
        FileCheckpointStore store = new FileCheckpointStore(project, "cross-process-run");
        store.withMutationLock(() -> {
            try {
                Files.writeString(entered, "entered");
                while (hold && Files.notExists(release)) {
                    Thread.sleep(5);
                }
                return null;
            } catch (Exception error) {
                throw new IllegalStateException(error);
            }
        });
    }
}
