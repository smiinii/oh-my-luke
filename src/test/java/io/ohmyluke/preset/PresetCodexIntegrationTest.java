package io.ohmyluke.preset;

import static org.junit.jupiter.api.Assertions.*;
import io.ohmyluke.ai.codex.CodexCliConfiguration;
import io.ohmyluke.ai.codex.CodexCliRuntime;
import io.ohmyluke.state.ProjectPermissionManager;
import io.ohmyluke.state.ProjectPermissionStore;
import io.ohmyluke.tool.PlatformProcessSandbox;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

class PresetCodexIntegrationTest {
    @TempDir Path project;

    @Test
    @EnabledIfEnvironmentVariable(named = "OML_CODEX_INTEGRATION", matches = "true")
    void realCodexProposalIsAppliedAndValidatedInDisposableTestProject() throws Exception {
        Files.writeString(project.resolve("hello.txt"), "old");
        Clock clock = Clock.systemUTC();
        var service = new PresetRunService(project, task -> new CodexCliRuntime(
                CodexCliConfiguration.defaults(project).withTimeout(Duration.ofMinutes(2))),
                new ProjectPermissionManager(new ProjectPermissionStore(project), clock), PlatformProcessSandbox.detect(), clock);
        service.start("real-preset", new TaskSpec(1, "Replace hello.txt with exactly OML_PRESET_OK. Return the requested JSON only.",
                "hello.txt", ExecutionMode.DIRECT, 1, 0, 180_000, 2,
                new ValidationSpec(List.of("OML_PRESET_OK"), List.of("old"), null), null, null));
        PresetResult result = service.resume("real-preset");
        assertEquals(PresetStatus.SUCCEEDED, result.status(), result.toString());
        assertEquals(1, result.attempts());
        assertTrue(result.allTokenUsageAvailable());
        assertTrue(result.recordedUsage() > 0);
        assertTrue(Files.readString(project.resolve("hello.txt")).contains("OML_PRESET_OK"));
        assertEquals(result, service.resume("real-preset"));
    }
}
