package io.ohmyluke.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import io.ohmyluke.policy.PermissionGrantLedger;
import io.ohmyluke.policy.ToolCapability;
import io.ohmyluke.policy.ToolPermissionGrant;
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
        Path sibling = Files.writeString(temporaryDirectory.resolve("sibling-secret.txt"), "sibling-hidden");
        ProcessTool tool = tool(project, sandbox);

        ProcessToolResult readOutside = tool.execute(request(
                "read-outside",
                Path.of("/bin/cat"),
                List.of(outside.toString())));
        ProcessToolResult writeCopy = tool.execute(request(
                "write-copy",
                Path.of("/usr/bin/touch"),
                List.of("created.txt")));

        assertNotEquals(0, readOutside.exitCode(), diagnostic(readOutside));
        assertFalse(readOutside.standardOutput().contains("never-visible"));
        ProcessToolResult readSibling = tool.execute(request(
                "read-sibling",
                Path.of("/bin/cat"),
                List.of(sibling.toString())));
        assertNotEquals(0, readSibling.exitCode(), diagnostic(readSibling));
        assertFalse(readSibling.standardOutput().contains("sibling-hidden"));
        assertEquals(0, writeCopy.exitCode(), diagnostic(writeCopy));
        assertFalse(Files.exists(project.resolve("created.txt")));
    }

    @Test
    void realBubblewrapDoesNotBindSiblingFilesFromTheDisposableParent() throws Exception {
        LinuxBubblewrapSandbox sandbox = new LinuxBubblewrapSandbox();
        Assumptions.assumeTrue(sandbox.available(), "bubblewrap is not installed");
        Path parent = Files.createTempDirectory(
                Path.of(System.getProperty("java.io.tmpdir")).toRealPath(),
                "oml-bwrap-parent-");
        try {
            Path workspace = Files.createDirectory(parent.resolve("project"));
            Path home = Files.createDirectory(parent.resolve("home"));
            Path sibling = Files.writeString(parent.resolve("sibling-secret.txt"), "must-stay-hidden");
            ProcessSandboxSpec specification = new ProcessSandboxSpec(
                    Path.of("/bin/cat"),
                    List.of(sibling.toString()),
                    workspace,
                    workspace,
                    home,
                    false);

            Process process = new ProcessBuilder(sandbox.prepare(specification).command()).start();
            String output = new String(process.getInputStream().readAllBytes());
            String error = new String(process.getErrorStream().readAllBytes());
            assertEquals(true, process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS), error);
            assertNotEquals(0, process.exitValue());
            assertFalse(output.contains("must-stay-hidden"));
        } finally {
            FileCheckpointStore.deleteTree(parent);
        }
    }

    @Test
    void realBubblewrapBlocksNetworkUntilTheExactProcessIsApproved() throws Exception {
        LinuxBubblewrapSandbox sandbox = new LinuxBubblewrapSandbox();
        Assumptions.assumeTrue(sandbox.available(), "bubblewrap is not installed");
        Assumptions.assumeTrue(Files.isExecutable(Path.of("/usr/bin/curl")), "curl is not installed");
        Path project = Files.createDirectory(temporaryDirectory.resolve("network-project"));
        try (ExecutorService listener = Executors.newVirtualThreadPerTaskExecutor();
                ServerSocket server = new ServerSocket(0)) {
            AtomicBoolean connected = new AtomicBoolean();
            listener.submit(() -> {
                try (java.net.Socket ignored = server.accept()) {
                    connected.set(true);
                } catch (IOException ignored) {
                    // Test cleanup or the isolated network branch.
                }
            });
            List<String> curlArguments = List.of(
                    "--max-time", "1", "http://127.0.0.1:" + server.getLocalPort());
            ProcessTool local = tool(project, sandbox);
            ProcessToolResult blocked = local.execute(request(
                    "network-blocked",
                    Path.of("/usr/bin/curl"),
                    curlArguments));
            assertFalse(connected.get());
            ProcessToolRequest request = request(
                            "network-approved",
                            Path.of("/usr/bin/curl"),
                            curlArguments)
                    .withCapability(ToolCapability.NETWORK_ACCESS, "network:any");
            ToolPermissionGrant grant = ToolPermissionGrant.once(
                    "network-grant",
                    local.permissionRequest(request),
                    System.currentTimeMillis() + 60_000);
            ProcessTool approved = tool(project, sandbox, List.of(grant));

            ProcessToolResult approvedResult = approved.execute(request);
            for (int attempt = 0; attempt < 20 && !connected.get(); attempt++) {
                Thread.sleep(10);
            }
            assertNotEquals(0, blocked.exitCode(), diagnostic(blocked));
            assertEquals(true, connected.get(), diagnostic(approvedResult));
        }
    }

    @Test
    void realBubblewrapTimeoutRemovesDescendantsFromItsPidNamespace() throws Exception {
        LinuxBubblewrapSandbox sandbox = new LinuxBubblewrapSandbox();
        Assumptions.assumeTrue(sandbox.available(), "bubblewrap is not installed");
        Assumptions.assumeTrue(Files.isExecutable(Path.of("/usr/bin/python3")), "python3 is not installed");
        Path project = Files.createDirectory(temporaryDirectory.resolve("timeout-project"));
        String marker = "oml-bwrap-child-" + java.util.UUID.randomUUID();
        String program = "import subprocess,sys,time; "
                + "subprocess.Popen([sys.executable,'-c','import time; time.sleep(30)','" + marker + "']); "
                + "time.sleep(30)";
        ProcessToolRequest request = request(
                        "timeout-tree",
                        Path.of("/usr/bin/python3"),
                        List.of("-c", program))
                .withTimeout(Duration.ofMillis(200));

        ProcessToolResult result = tool(project, sandbox).execute(request);
        for (int attempt = 0; attempt < 20 && processWithArgument(marker); attempt++) {
            Thread.sleep(10);
        }

        assertEquals(true, result.timedOut(), diagnostic(result));
        assertFalse(processWithArgument(marker));
    }

    private static ProcessTool tool(Path project, ProcessSandbox sandbox) {
        return tool(project, sandbox, List.of());
    }

    private static ProcessTool tool(
            Path project,
            ProcessSandbox sandbox,
            List<ToolPermissionGrant> grants) {
        ToolPermissionPolicy permissions = new ToolPermissionPolicy(
                new PermissionGrantLedger(grants),
                project,
                false,
                Clock.systemUTC());
        return new ProcessTool(project, "run-001", permissions, sandbox);
    }

    private static boolean processWithArgument(String marker) {
        return ProcessHandle.allProcesses().anyMatch(handle -> handle.info().arguments()
                .map(arguments -> java.util.Arrays.stream(arguments).anyMatch(argument -> argument.contains(marker)))
                .orElse(false));
    }

    private static String diagnostic(ProcessToolResult result) {
        return "exit=" + result.exitCode()
                + ", executed=" + result.executed()
                + ", timedOut=" + result.timedOut()
                + ", detail=" + result.detail()
                + ", stderr=" + result.standardError();
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
