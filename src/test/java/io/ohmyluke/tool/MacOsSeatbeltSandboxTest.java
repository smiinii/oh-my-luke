package io.ohmyluke.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.ohmyluke.policy.PermissionGrantLedger;
import io.ohmyluke.policy.ToolCapability;
import io.ohmyluke.policy.ToolPermissionPolicy;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@EnabledOnOs(OS.MAC)
class MacOsSeatbeltSandboxTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void permitsWorkspaceWritesWithoutChangingTheRealProject() throws IOException {
        Path project = Files.createDirectory(temporaryDirectory.resolve("project"));
        ProcessTool tool = tool(project);
        ProcessToolRequest request = request(
                "touch-1",
                Path.of("/usr/bin/touch"),
                List.of("created.txt"));

        ProcessToolResult result = tool.execute(request);

        assertEquals(0, result.exitCode(), result.standardError());
        assertFalse(Files.exists(project.resolve("created.txt")));
    }

    @Test
    void blocksReadsOutsideTheIsolatedWorkspaceAndSystemRuntime() throws IOException {
        Path project = Files.createDirectory(temporaryDirectory.resolve("project"));
        Path secret = Files.writeString(temporaryDirectory.resolve("outside-secret.txt"), "never-visible");
        ProcessTool tool = tool(project);
        ProcessToolRequest request = request(
                "cat-1",
                Path.of("/bin/cat"),
                List.of(secret.toString()));

        ProcessToolResult result = tool.execute(request);

        org.junit.jupiter.api.Assertions.assertNotEquals(0, result.exitCode());
        assertFalse(result.standardOutput().contains("never-visible"));
    }

    @Test
    void blocksAnotherFileInPrivateTmp() throws IOException {
        Path project = Files.createDirectory(temporaryDirectory.resolve("project"));
        Path secret = Files.createTempFile(Path.of("/private/tmp"), "oml-outside-", ".txt");
        try {
            Files.writeString(secret, "private-tmp-secret");
            ProcessToolResult result = tool(project).execute(request(
                    "cat-private-tmp",
                    Path.of("/bin/cat"),
                    List.of(secret.toString())));

            org.junit.jupiter.api.Assertions.assertNotEquals(0, result.exitCode());
            assertFalse(result.standardOutput().contains("private-tmp-secret"));
        } finally {
            Files.deleteIfExists(secret);
        }
    }

    @Test
    void deniesChildProcessCreationInsteadOfLeavingDescendantsBehind() throws IOException {
        Path project = Files.createDirectory(temporaryDirectory.resolve("project"));
        Path java = Path.of(System.getProperty("java.home"), "bin", "java").toRealPath();
        ProcessToolRequest request = new ProcessToolRequest(
                "spawn-child",
                java,
                List.of(
                        "-cp",
                        System.getProperty("java.class.path"),
                        ProcessToolFixture.class.getName(),
                        "spawn",
                        java.toString()),
                Path.of("."),
                Map.of(),
                Duration.ofSeconds(5),
                4096,
                ToolCapability.LOCAL_PROCESS,
                "local");

        ProcessToolResult result = tool(project).execute(request);

        org.junit.jupiter.api.Assertions.assertNotEquals(0, result.exitCode());
        assertFalse(result.timedOut());
    }

    @Test
    void blocksNetworkByDefaultAndEnablesItOnlyAfterAnExactGrant() throws Exception {
        Path project = Files.createDirectory(temporaryDirectory.resolve("project"));
        try (ExecutorService listener = Executors.newVirtualThreadPerTaskExecutor();
                ServerSocket server = new ServerSocket(0)) {
            AtomicBoolean connected = new AtomicBoolean();
            listener.submit(() -> {
                try (java.net.Socket ignored = server.accept()) {
                    connected.set(true);
                } catch (IOException ignored) {
                    // The default-denied branch closes the listener without a connection.
                }
            });
            ProcessTool blockedTool = tool(project);
            ProcessToolRequest blockedRequest = request(
                    "curl-blocked",
                    Path.of("/usr/bin/curl"),
                    List.of("--max-time", "1", "http://127.0.0.1:" + server.getLocalPort()));

            blockedTool.execute(blockedRequest);
            assertFalse(connected.get());
        }

        try (ExecutorService listener = Executors.newVirtualThreadPerTaskExecutor();
                ServerSocket server = new ServerSocket(0)) {
            AtomicBoolean connected = new AtomicBoolean();
            listener.submit(() -> {
                try (java.net.Socket ignored = server.accept()) {
                    connected.set(true);
                } catch (IOException ignored) {
                    // Test cleanup.
                }
            });
            ProcessToolRequest request = request(
                            "curl-allowed",
                            Path.of("/usr/bin/curl"),
                            List.of("--max-time", "1", "http://127.0.0.1:" + server.getLocalPort()))
                    .withCapability(ToolCapability.NETWORK_ACCESS, "network:any");
            ProcessTool withoutGrant = tool(project);
            io.ohmyluke.policy.ToolPermissionGrant grant = io.ohmyluke.policy.ToolPermissionGrant.once(
                    "grant-network",
                    withoutGrant.permissionRequest(request),
                    System.currentTimeMillis() + 60_000);
            ToolPermissionPolicy permissions = new ToolPermissionPolicy(
                    new PermissionGrantLedger(List.of(grant)),
                    project,
                    false,
                    Clock.systemUTC());
            ProcessTool allowedTool = new ProcessTool(
                    project,
                    "run-001",
                    permissions,
                    new MacOsSeatbeltSandbox());

            allowedTool.execute(request);
            for (int attempt = 0; attempt < 20 && !connected.get(); attempt++) {
                Thread.sleep(10);
            }
            assertEquals(true, connected.get());
        }
    }

    private static ProcessTool tool(Path project) {
        ToolPermissionPolicy permissions = new ToolPermissionPolicy(
                new PermissionGrantLedger(List.of()),
                project,
                false,
                Clock.systemUTC());
        return new ProcessTool(project, "run-001", permissions, new MacOsSeatbeltSandbox());
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
