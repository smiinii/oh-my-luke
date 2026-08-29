package io.ohmyluke.ai.codex;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

public final class CodexInvocationStoreProcessFixture {
    private CodexInvocationStoreProcessFixture() {}

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 5) {
            throw new IllegalArgumentException(
                    "expected project, mode, started, entered and release paths");
        }
        Path project = Path.of(arguments[0]).toRealPath();
        String mode = arguments[1];
        Path started = Path.of(arguments[2]);
        Path entered = Path.of(arguments[3]);
        Path release = Path.of(arguments[4]);
        CodexInvocationStore store = new CodexInvocationStore(project, 1024);

        Files.writeString(started, "started");
        try (var ignored = store.lock("cross-jvm-lock")) {
            Files.writeString(entered, "entered");
            if (mode.equals("hold")) {
                awaitRelease(release);
            } else if (!mode.equals("wait")) {
                throw new IllegalArgumentException("unknown mode");
            }
        }
    }

    private static void awaitRelease(Path release) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
        while (Files.notExists(release) && Instant.now().isBefore(deadline)) {
            Thread.sleep(10);
        }
        if (Files.notExists(release)) {
            throw new IllegalStateException("release marker was not created");
        }
    }
}
