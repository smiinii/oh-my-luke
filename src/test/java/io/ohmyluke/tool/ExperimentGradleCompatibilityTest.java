package io.ohmyluke.tool;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ohmyluke.policy.PermissionGrantLedger;
import io.ohmyluke.policy.ToolCapability;
import io.ohmyluke.policy.ToolPermissionPolicy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** Readiness evidence: the current macOS product must not be claimed Gradle-ready. */
@EnabledOnOs(OS.MAC)
class ExperimentGradleCompatibilityTest {
    @TempDir Path temporary;

    @Test
    void currentMacOsProcessToolCannotRunTheBenchmarkGradleWrapper() throws Exception {
        Path project = Files.createDirectory(temporary.resolve("project"));
        for (String name : List.of("gradlew", "gradle/wrapper/gradle-wrapper.jar",
                "gradle/wrapper/gradle-wrapper.properties")) {
            Path target = project.resolve(name);
            Files.createDirectories(target.getParent());
            Files.copy(Path.of(name), target);
        }
        project.resolve("gradlew").toFile().setExecutable(true);
        Path fixture = Path.of("experiments/01-token/fixtures/common");
        try (var paths = Files.walk(fixture)) {
            for (Path source : paths.filter(Files::isRegularFile).toList()) {
                Path target = project.resolve(fixture.relativize(source));
                Files.createDirectories(target.getParent());
                Files.copy(source, target);
            }
        }
        var policy = new ToolPermissionPolicy(new PermissionGrantLedger(List.of()), project, false, Clock.systemUTC());
        var tool = new ProcessTool(project, "benchmark-gradle-probe", policy, new MacOsSeatbeltSandbox());
        var request = new ProcessToolRequest("gradle-probe", project.resolve("gradlew").toAbsolutePath(),
                List.of("--offline", "--no-daemon", "test"), Path.of("."), Map.of(),
                Duration.ofSeconds(15), 8192, ToolCapability.LOCAL_PROCESS, "local");

        ProcessToolResult result = tool.execute(request);

        assertTrue(result.executed(), "The probe must reach sandboxed execution: " + result.detail());
        assertFalse(result.executed() && Integer.valueOf(0).equals(result.exitCode()),
                "If Gradle is now supported, update experiment feasibility and this evidence test");
    }
}
