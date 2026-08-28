package io.ohmyluke.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import io.ohmyluke.policy.PermissionGrantLedger;
import io.ohmyluke.policy.ToolCapability;
import io.ohmyluke.policy.ToolPermissionPolicy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** Executes the real Linux namespace adapter in CI; structural command tests are not sufficient. */
@EnabledOnOs(OS.LINUX)
class LinuxBubblewrapIntegrationTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void realBubblewrapHidesOutsideFilesAndNeverMutatesTheCheckout() throws IOException {
        LinuxBubblewrapSandbox sandbox = new LinuxBubblewrapSandbox();
        Assumptions.assumeTrue(sandbox.available(), "bubblewrap is not installed");
        Path project = Files.createDirectory(temporaryDirectory.resolve("project"));
        Path outside = Files.writeString(temporaryDirectory.resolve("outside-secret.txt"), "never-visible");
        ProcessTool tool = tool(project, sandbox);

        ProcessToolResult readOutside = tool.execute(request(
                "read-outside",
                Path.of("/bin/cat"),
                List.of(outside.toString())));
        ProcessToolResult writeCopy = tool.execute(request(
                "write-copy",
                Path.of("/usr/bin/touch"),
                List.of("created.txt")));

        assertNotEquals(0, readOutside.exitCode());
        assertFalse(readOutside.standardOutput().contains("never-visible"));
        assertEquals(0, writeCopy.exitCode(), writeCopy.standardError());
        assertFalse(Files.exists(project.resolve("created.txt")));
    }

    private static ProcessTool tool(Path project, ProcessSandbox sandbox) {
        ToolPermissionPolicy permissions = new ToolPermissionPolicy(
                new PermissionGrantLedger(List.of()),
                project,
                false,
                Clock.systemUTC());
        return new ProcessTool(project, "run-001", permissions, sandbox);
    }

    private static ProcessToolRequest request(String operationId, Path executable, List<String> arguments) {
        return new ProcessToolRequest(
                operationId,
                executable,
                arguments,
                Path.of("."),
                Map.of(),
                Duration.ofSeconds(5),
                4096,
                ToolCapability.LOCAL_PROCESS,
                "local");
    }
}
