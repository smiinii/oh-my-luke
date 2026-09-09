package io.ohmyluke.ai.codex;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CodexCatalogClientTest {
    @TempDir Path root;

    @Test void realStdioTransportReturnsOnlyValidatedModelsAndNoAccountData() {
        var result = new CodexCatalogClient().load(command("valid"), root, Duration.ofSeconds(3));
        assertEquals(CodexModelCatalog.Status.AVAILABLE, result.status());
        assertEquals("actual-model", result.models().getFirst().model());
        assertFalse(result.toString().contains("private"));
    }

    @ParameterizedTest @ValueSource(strings = {"silent", "flood", "stderr", "wrong-id", "duplicate", "error", "request", "notifications", "account-changed"})
    void unresponsiveOrInvalidServersFailWithoutLeakingRawOutput(String mode) {
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            var result = new CodexCatalogClient().load(command(mode), root, Duration.ofMillis(500));
            assertEquals(CodexModelCatalog.Status.UNAVAILABLE, result.status());
            assertTrue(result.models().isEmpty());
            assertFalse(result.toString().contains("private"));
        });
    }

    @Test void timeoutCleansUpObservedDescendantsAndTheirInheritedPipes() throws Exception {
        Path pid = root.resolve("child.pid");
        var command = new ArrayList<>(command("child")); command.add(pid.toString());
        var result = new CodexCatalogClient().load(command, root, Duration.ofSeconds(2));
        assertEquals(CodexModelCatalog.Status.UNAVAILABLE, result.status());
        assertTrue(Files.exists(pid));
        long child = Long.parseLong(Files.readString(pid));
        assertFalse(ProcessHandle.of(child).map(ProcessHandle::isAlive).orElse(false));
    }

    @Test void unavailableProcessObservationDoesNotStartTheServerOrExposeTheOperatingSystemError() {
        var client = new CodexCatalogClient(() -> { throw new IllegalStateException("private OS diagnostic"); });
        var result = client.load(command("silent"), root, Duration.ofSeconds(1));
        assertEquals(CodexModelCatalog.unavailable(), result);
        assertFalse(result.toString().contains("private"));
    }

    private List<String> command(String mode) {
        String classpath = java.util.Arrays.stream(System.getProperty("java.class.path").split(java.util.regex.Pattern.quote(java.io.File.pathSeparator)))
                .map(path -> Path.of(path).toAbsolutePath().toString()).collect(java.util.stream.Collectors.joining(java.io.File.pathSeparator));
        return List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp", classpath, CatalogServerFixture.class.getName(), mode);
    }
}
