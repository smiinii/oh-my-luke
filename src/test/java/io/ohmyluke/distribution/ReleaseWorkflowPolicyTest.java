package io.ohmyluke.distribution;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ReleaseWorkflowPolicyTest {
    @Test
    void dryRunWorkflowHasReadOnlyPermissionsAndNoPublishingPath() throws Exception {
        String workflow = Files.readString(Path.of(".github/workflows/release-candidate-dry-run.yml"));

        assertTrue(workflow.contains("permissions:\n  contents: read"));
        assertTrue(workflow.contains("pull_request:"));
        assertTrue(workflow.contains("workflow_dispatch:"));
        assertTrue(workflow.contains("- \"examples/**\""));
        assertTrue(workflow.contains("- \"gradle/**\""));
        assertTrue(workflow.contains("- \"gradle.properties\""));
        assertTrue(workflow.contains("- \"settings.gradle.kts\""));
        assertTrue(workflow.contains("- \"gradlew\""));
        assertFalse(workflow.contains("contents: write"));
        assertFalse(workflow.contains("pull_request_target:"));
        assertFalse(workflow.contains("workflow_run:"));
        assertFalse(workflow.contains("gh release"));
        assertFalse(workflow.contains("release create"));

        assertTrue(workflow.contains("macos-15"));
        assertTrue(workflow.contains("ubuntu-24.04"));
        assertTrue(workflow.contains("actions/checkout@v7"));
        assertTrue(workflow.contains("actions/setup-java@v6"));
        assertTrue(workflow.contains("actions/upload-artifact@v7"));
        assertTrue(workflow.contains("actions/download-artifact@v8"));
        assertTrue(workflow.contains("RC_VERSION: 0.1.0-rc.1"));
    }
}
