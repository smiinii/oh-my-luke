package io.ohmyluke.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ohmyluke.policy.PermissionGrantLedger;
import io.ohmyluke.policy.ToolCapability;
import io.ohmyluke.policy.ToolPermission;
import io.ohmyluke.policy.ToolPermissionGrant;
import io.ohmyluke.policy.ToolPermissionPolicy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProcessToolTest {
    private static final Instant NOW = Instant.parse("2026-08-28T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @TempDir
    Path temporaryDirectory;

    @Test
    void failsClosedWhenNoVerifiedSandboxIsAvailable() throws IOException {
        Path project = Files.createDirectory(temporaryDirectory.resolve("project"));
        ProcessTool tool = tool(project, new UnavailableProcessSandbox("not installed"), false, List.of());

        ProcessToolResult result = tool.execute(javaRequest(project, "write"));

        assertEquals(ToolPermission.DENY, result.permission().permission());
        assertEquals("sandbox.unavailable", result.permission().reasonCode());
        assertFalse(result.executed());
    }

    @Test
    void runsLocalCommandsInAnIsolatedCopyWithoutChangingTheProject() throws IOException {
        Path project = Files.createDirectory(temporaryDirectory.resolve("project"));
        Files.writeString(project.resolve("source.txt"), "source");
        ProcessTool tool = tool(project, new TestVerifiedSandbox(), false, List.of());

        ProcessToolResult result = tool.execute(javaRequest(project, "write"));

        assertEquals(ToolPermission.ALLOW, result.permission().permission());
        assertTrue(result.executed());
        assertEquals(0, result.exitCode());
        assertFalse(Files.exists(project.resolve("generated-by-process.txt")));
        assertEquals("source", Files.readString(project.resolve("source.txt")));
    }

    @Test
    void asksForNetworkOrExternalEffectsUntilTheExactScopeIsGranted() throws IOException {
        Path project = Files.createDirectory(temporaryDirectory.resolve("project"));
        ProcessToolRequest request = javaRequest(project, "write").withCapability(
                ToolCapability.EXTERNAL_WRITE,
                "git:origin");
        ProcessTool first = tool(project, new TestVerifiedSandbox(), false, List.of());
        ProcessToolResult asked = first.execute(request);
        ToolPermissionGrant grant = ToolPermissionGrant.forProject(
                "grant-push",
                first.permissionRequest(request),
                NOW.plusSeconds(60).toEpochMilli());
        ProcessTool approved = tool(project, new TestVerifiedSandbox(), false, List.of(grant));

        ProcessToolResult allowed = approved.execute(request);

        assertEquals(ToolPermission.ASK, asked.permission().permission());
        assertEquals(ToolPermission.ALLOW, allowed.permission().permission());
    }

    @Test
    void projectGrantIsBoundToTheResolvedExecutableAndEveryArgument() throws IOException {
        Path project = Files.createDirectory(temporaryDirectory.resolve("project"));
        ProcessToolRequest approvedRequest = javaRequest(project, "write").withCapability(
                ToolCapability.EXTERNAL_WRITE,
                "git:origin");
        ProcessTool first = tool(project, new TestVerifiedSandbox(), false, List.of());
        ToolPermissionGrant grant = ToolPermissionGrant.forProject(
                "grant-exact-process",
                first.permissionRequest(approvedRequest),
                NOW.plusSeconds(60).toEpochMilli());
        ProcessTool approved = tool(project, new TestVerifiedSandbox(), false, List.of(grant));
        ProcessToolRequest differentCommand = javaRequest(project, "large", "1").withCapability(
                ToolCapability.EXTERNAL_WRITE,
                "git:origin");

        ProcessToolResult result = approved.execute(differentCommand);

        assertEquals(ToolPermission.ASK, result.permission().permission());
        assertFalse(result.executed());
    }

    @Test
    void neverRunsShellWrappersEvenInAutonomousMode() throws IOException {
        Path project = Files.createDirectory(temporaryDirectory.resolve("project"));
        ProcessTool tool = tool(project, new TestVerifiedSandbox(), true, List.of());
        ProcessToolRequest request = new ProcessToolRequest(
                "shell-1",
                Path.of("/bin/sh"),
                List.of("-c", "touch escaped"),
                Path.of("."),
                Map.of(),
                Duration.ofSeconds(5),
                1024,
                ToolCapability.LOCAL_PROCESS,
                "local");

        ProcessToolResult result = tool.execute(request);

        assertEquals(ToolPermission.DENY, result.permission().permission());
        assertEquals("process.shell-deny", result.permission().reasonCode());
    }

    @Test
    void resolvesExecutableBeforeRejectingShellSymlinks() throws IOException {
        Path project = Files.createDirectory(temporaryDirectory.resolve("project"));
        Path link = Files.createSymbolicLink(temporaryDirectory.resolve("runner"), Path.of("/bin/sh"));
        ProcessTool tool = tool(project, new TestVerifiedSandbox(), true, List.of());
        ProcessToolRequest request = new ProcessToolRequest(
                "shell-link",
                link,
                List.of("-c", "exit 0"),
                Path.of("."),
                Map.of(),
                Duration.ofSeconds(5),
                1024,
                ToolCapability.LOCAL_PROCESS,
                "local");

        ProcessToolResult result = tool.execute(request);

        assertEquals(ToolPermission.DENY, result.permission().permission());
        assertEquals("process.shell-deny", result.permission().reasonCode());
    }

    @Test
    void doesNotInheritHostSecretsAndRejectsEveryCallerEnvironmentValue() throws IOException {
        Path project = Files.createDirectory(temporaryDirectory.resolve("project"));
        ProcessToolRequest safe = javaRequest(project, "env", "OML_SAFE_VALUE");
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> safe.withEnvironment(Map.of("OML_SAFE_VALUE", "visible")));
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> safe.withEnvironment(Map.of("API_TOKEN", "secret")));
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new ProcessToolRequest(
                        "secret-arg",
                        Path.of("/usr/bin/curl"),
                        List.of("--token", "raw-value"),
                        Path.of("."),
                        Map.of(),
                        Duration.ofSeconds(5),
                        1024,
                        ToolCapability.NETWORK_ACCESS,
                        "network:any"));
        for (List<String> credentialArguments : List.of(
                List.of("-u", "alice:hunter2"),
                List.of("--proxy-user", "alice:hunter2"),
                List.of("--oauth2-bearer", "opaque-secret-value"),
                List.of("--_auth=opaque-secret-value"),
                List.of("-H", "X_API_KEY: opaque-secret-value"))) {
            org.junit.jupiter.api.Assertions.assertThrows(
                    IllegalArgumentException.class,
                    () -> new ProcessToolRequest(
                            "credential-option",
                            Path.of("/usr/bin/curl"),
                            credentialArguments,
                            Path.of("."),
                            Map.of(),
                            Duration.ofSeconds(5),
                            1024,
                            ToolCapability.NETWORK_ACCESS,
                            "network:any"));
        }
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> new ProcessToolRequest(
                "safe-auth-options",
                Path.of("/usr/bin/curl"),
                List.of("--cookie-jar", "cookies.out", "--no-auth-cache", "https://example.com"),
                Path.of("."),
                Map.of(),
                Duration.ofSeconds(5),
                1024,
                ToolCapability.NETWORK_ACCESS,
                "network:any"));
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new ProcessToolRequest(
                        "secret-header",
                        Path.of("/usr/bin/curl"),
                        List.of("-H", "X-Api-Key: opaque-secret-value", "https://example.com"),
                        Path.of("."),
                        Map.of(),
                        Duration.ofSeconds(5),
                        1024,
                        ToolCapability.NETWORK_ACCESS,
                        "network:any"));
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new ProcessToolRequest(
                        "secret-client-flag",
                        Path.of("/usr/bin/curl"),
                        List.of("--client-secret", "opaque-secret-value"),
                        Path.of("."),
                        Map.of(),
                        Duration.ofSeconds(5),
                        1024,
                        ToolCapability.NETWORK_ACCESS,
                        "network:any"));
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new ProcessToolRequest(
                        "secret-url",
                        Path.of("/usr/bin/curl"),
                        List.of("https://alice:hunter2@example.com/api"),
                        Path.of("."),
                        Map.of(),
                        Duration.ofSeconds(5),
                        1024,
                        ToolCapability.NETWORK_ACCESS,
                        "network:any"));
    }

    @Test
    void redactsLikelySecretsAndCapsOutputWhileContinuingToDrainIt() throws IOException {
        Path project = Files.createDirectory(temporaryDirectory.resolve("project"));
        ProcessTool tool = tool(project, new TestVerifiedSandbox(), false, List.of());

        ProcessToolResult secret = tool.execute(javaRequest(project, "secret"));
        ProcessToolResult opaque = tool.execute(javaRequest(project, "opaque-secrets"));
        ProcessToolResult authVariants = tool.execute(javaRequest(project, "auth-variants"));
        ProcessToolResult partial = tool.execute(javaRequest(project, "secret").withOutputLimit(10));
        ProcessToolResult large = tool.execute(javaRequest(project, "large", "4096").withOutputLimit(128));

        assertFalse(secret.standardOutput().contains("ghp_"));
        assertTrue(secret.standardOutput().contains("[REDACTED]"));
        assertFalse(opaque.standardOutput().contains("AKIA"));
        assertFalse(opaque.standardOutput().contains("opaque-secret"));
        assertFalse(authVariants.standardOutput().contains("dXNlcjpwYXNz"));
        assertFalse(authVariants.standardOutput().contains("opaque-credential"));
        assertFalse(authVariants.standardOutput().contains("opaque-request-credential"));
        assertFalse(authVariants.standardOutput().contains("opaque-cookie"));
        assertFalse(authVariants.standardOutput().contains("opaque-equals-cookie"));
        assertFalse(authVariants.standardOutput().contains("opaque-equals-set-cookie"));
        assertFalse(partial.standardOutput().contains("ghp_"));
        assertTrue(partial.standardOutput().contains("[REDACTED]"));
        assertEquals(128, large.standardOutput().length());
        assertTrue(large.outputTruncated());
    }

    @Test
    void timesOutAndTerminatesLongRunningProcesses() throws IOException {
        Path project = Files.createDirectory(temporaryDirectory.resolve("project"));
        ProcessTool tool = tool(project, new TestVerifiedSandbox(), false, List.of());
        ProcessToolRequest request = javaRequest(project, "sleep", "5000")
                .withTimeout(Duration.ofMillis(100));

        ProcessToolResult result = tool.execute(request);

        assertTrue(result.executed());
        assertTrue(result.timedOut());
        assertEquals(-1, result.exitCode());
    }

    @Test
    void excludesOmlGitAndCredentialFilesFromTheProcessWorkspace() throws IOException {
        Path project = Files.createDirectory(temporaryDirectory.resolve("project"));
        Files.createDirectories(project.resolve(".oml")).resolve("state.json");
        Files.writeString(project.resolve(".oml/state.json"), "state");
        Files.createDirectories(project.resolve(".git"));
        Files.writeString(project.resolve(".env"), "TOKEN=secret");
        Files.writeString(project.resolve(".npmrc"), "_auth=opaque");
        Files.writeString(project.resolve(".netrc"), "password opaque");
        Files.writeString(project.resolve("settings.xml"), "<password>opaque</password>");
        ProcessWorkspace workspace = ProcessWorkspace.create(project, "run-001", "copy-1");

        try {
            assertFalse(Files.exists(workspace.projectRoot().resolve(".oml")));
            assertFalse(Files.exists(workspace.projectRoot().resolve(".git")));
            assertFalse(Files.exists(workspace.projectRoot().resolve(".env")));
            assertFalse(Files.exists(workspace.projectRoot().resolve(".npmrc")));
            assertFalse(Files.exists(workspace.projectRoot().resolve(".netrc")));
            assertFalse(Files.exists(workspace.projectRoot().resolve("settings.xml")));
        } finally {
            workspace.close();
        }
    }

    private static ProcessTool tool(
            Path project,
            ProcessSandbox sandbox,
            boolean autonomous,
            List<ToolPermissionGrant> grants) {
        ToolPermissionPolicy permissions = new ToolPermissionPolicy(
                new PermissionGrantLedger(grants),
                project,
                autonomous,
                CLOCK);
        return new ProcessTool(project, "run-001", permissions, sandbox);
    }

    private static ProcessToolRequest javaRequest(Path project, String... fixtureArguments) {
        String javaHome = System.getProperty("java.home");
        Path java = Path.of(javaHome, "bin", isWindows() ? "java.exe" : "java");
        java.util.ArrayList<String> arguments = new java.util.ArrayList<>();
        arguments.add("-cp");
        arguments.add(System.getProperty("java.class.path"));
        arguments.add(ProcessToolFixture.class.getName());
        arguments.addAll(List.of(fixtureArguments));
        return new ProcessToolRequest(
                "process-" + fixtureArguments[0],
                java,
                arguments,
                Path.of("."),
                Map.of(),
                Duration.ofSeconds(5),
                8192,
                ToolCapability.LOCAL_PROCESS,
                "local:" + project.toAbsolutePath().normalize());
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win");
    }

    private static final class TestVerifiedSandbox implements ProcessSandbox {
        @Override
        public boolean available() {
            return true;
        }

        @Override
        public String unavailableReason() {
            return "";
        }

        @Override
        public SandboxLaunch prepare(ProcessSandboxSpec specification) {
            java.util.ArrayList<String> command = new java.util.ArrayList<>();
            command.add(specification.executable().toString());
            command.addAll(specification.arguments());
            return SandboxLaunch.direct(command);
        }
    }
}
