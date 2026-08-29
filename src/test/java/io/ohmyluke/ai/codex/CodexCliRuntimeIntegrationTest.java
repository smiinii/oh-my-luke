package io.ohmyluke.ai.codex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ohmyluke.ai.AiRequest;
import io.ohmyluke.ai.AiRuntimeResult;
import io.ohmyluke.ai.AiRuntimeStatus;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

class CodexCliRuntimeIntegrationTest {
    @TempDir
    Path project;

    @Test
    @EnabledIfEnvironmentVariable(named = "OML_CODEX_INTEGRATION", matches = "true")
    void invokesTheUsersAuthenticatedCodexCliWithoutAnApiKey() {
        CodexCliRuntime runtime = new CodexCliRuntime(CodexCliConfiguration
                .defaults(project)
                .withTimeout(Duration.ofMinutes(2)));

        CodexRuntimeProbe probe = runtime.probe();
        assertTrue(probe.installed(), "Codex CLI must be installed");
        assertTrue(probe.authenticated(), "Run codex login before this test");

        AiRuntimeResult result = runtime.invoke(new AiRequest(
                "real-codex-smoke",
                "Reply with exactly OML_CODEX_OK and no other text.",
                Map.of()));

        assertEquals(AiRuntimeStatus.SUCCESS, result.status());
        assertFalse(result.output().isBlank());
        assertTrue(result.output().contains("OML_CODEX_OK"));
        assertTrue(result.tokenUsage().available());
        assertTrue(result.usage() > 0);
    }
}
