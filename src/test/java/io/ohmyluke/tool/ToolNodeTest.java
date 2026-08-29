package io.ohmyluke.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ohmyluke.graph.NodeContext;
import io.ohmyluke.graph.NodeId;
import io.ohmyluke.graph.NodeResult;
import io.ohmyluke.graph.Outcome;
import io.ohmyluke.policy.PermissionGrantLedger;
import io.ohmyluke.policy.ToolCapability;
import io.ohmyluke.policy.ToolPermissionPolicy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ToolNodeTest {
    @TempDir
    Path project;

    @Test
    void fileNodeStoresContentAsAnArtifactAndReportsOneToolCall() throws IOException {
        Path source = Files.writeString(project.resolve("source.txt"), "content");
        ToolPermissionPolicy permissions = permissions(project);
        FileToolNode node = new FileToolNode(
                new NodeId("read"),
                new FileTool(project, "run-001", permissions, Clock.systemUTC()),
                FileToolRequest.read("read-1", source),
                new ToolArtifactStore(project, "run-001"));

        NodeResult result = node.execute(new NodeContext(Map.of(), 0));

        assertEquals(Outcome.SUCCESS, result.outcome());
        assertEquals(1, result.metrics().toolCalls());
        String artifact = result.statePatch().updates().get("tool.read.artifact");
        assertTrue(Files.exists(project.resolve(artifact)));
        assertFalse(result.statePatch().updates().containsValue("content"));
    }

    @Test
    void approvalRequestBecomesAStructuredNodeFailure() throws IOException {
        Path outside = Files.writeString(
                project.resolveSibling(project.getFileName() + "-outside.txt"),
                "outside");
        ToolPermissionPolicy permissions = permissions(project);
        FileToolNode node = new FileToolNode(
                new NodeId("outside"),
                new FileTool(project, "run-001", permissions, Clock.systemUTC()),
                FileToolRequest.read("outside-1", outside),
                new ToolArtifactStore(project, "run-001"));

        NodeResult result = node.execute(new NodeContext(Map.of(), 0));

        assertEquals(Outcome.FAILURE, result.outcome());
        assertEquals("permission", result.failureInfo().type());
        assertEquals("approval-required", result.failureInfo().code());
        assertEquals(1, result.metrics().toolCalls());
    }

    @Test
    void processNodeStoresRedactedOutputAndKeepsStateSmall() {
        ProcessToolRequest request = javaRequest();
        ProcessToolNode node = new ProcessToolNode(
                new NodeId("process"),
                new ProcessTool(project, "run-001", permissions(project), new TestSandbox()),
                request,
                new ToolArtifactStore(project, "run-001"));

        NodeResult result = node.execute(new NodeContext(Map.of(), 0));

        assertEquals(Outcome.SUCCESS, result.outcome());
        assertEquals(1, result.metrics().toolCalls());
        String artifact = result.statePatch().updates().get("tool.process.stdoutArtifact");
        assertTrue(Files.exists(project.resolve(artifact)));
        assertFalse(result.statePatch().updates().values().stream().anyMatch(value -> value.contains("ghp_")));
    }

    @Test
    void processFingerprintUsesUnambiguousLengthPrefixedFields() {
        ProcessTool tool = new ProcessTool(project, "run-001", permissions(project), new TestSandbox());
        ToolArtifactStore artifacts = new ToolArtifactStore(project, "run-001");
        ProcessToolNode combined = new ProcessToolNode(
                new NodeId("combined"),
                tool,
                requestWithArguments("fingerprint-1", List.of("a, b")),
                artifacts);
        ProcessToolNode separate = new ProcessToolNode(
                new NodeId("separate"),
                tool,
                requestWithArguments("fingerprint-1", List.of("a", "b")),
                artifacts);

        org.junit.jupiter.api.Assertions.assertNotEquals(combined.fingerprint(), separate.fingerprint());
    }

    @Test
    void processFingerprintUsesTheResolvedExecutableIdentity() throws IOException {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java").toRealPath();
        Path alternative = Files.isExecutable(Path.of("/usr/bin/true"))
                ? Path.of("/usr/bin/true")
                : java;
        org.junit.jupiter.api.Assumptions.assumeFalse(java.equals(alternative.toRealPath()));
        Path link = Files.createSymbolicLink(project.resolve("runner"), java);
        ProcessTool tool = new ProcessTool(project, "run-001", permissions(project), new TestSandbox());
        ToolArtifactStore artifacts = new ToolArtifactStore(project, "run-001");
        ProcessToolRequest request = new ProcessToolRequest(
                "resolved-executable",
                link,
                List.of("-version"),
                Path.of("."),
                Map.of(),
                Duration.ofSeconds(5),
                4096,
                ToolCapability.LOCAL_PROCESS,
                "local");
        ProcessToolNode before = new ProcessToolNode(new NodeId("before"), tool, request, artifacts);
        Files.delete(link);
        Files.createSymbolicLink(link, alternative);
        ProcessToolNode after = new ProcessToolNode(new NodeId("after"), tool, request, artifacts);

        org.junit.jupiter.api.Assertions.assertNotEquals(before.fingerprint(), after.fingerprint());
    }

    @Test
    void processSetupFailureHasItsOwnStructuredFailureCodeAndCause() {
        ProcessSandbox failing = new ProcessSandbox() {
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
                throw new ProcessToolException("fixture setup failure");
            }
        };
        ProcessToolNode node = new ProcessToolNode(
                new NodeId("setup"),
                new ProcessTool(project, "run-001", permissions(project), failing),
                javaRequest(),
                new ToolArtifactStore(project, "run-001"));

        NodeResult result = node.execute(new NodeContext(Map.of(), 0));

        assertEquals(Outcome.FAILURE, result.outcome());
        assertEquals("setup-failed", result.failureInfo().code());
        assertTrue(result.failureInfo().cause().contains("Process setup failed safely"));
    }

    private static ToolPermissionPolicy permissions(Path project) {
        return new ToolPermissionPolicy(
                new PermissionGrantLedger(List.of()),
                project,
                false,
                Clock.systemUTC());
    }

    private static ProcessToolRequest javaRequest() {
        Path java = Path.of(
                System.getProperty("java.home"),
                "bin",
                System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java");
        return new ProcessToolRequest(
                "process-1",
                java,
                List.of(
                        "-cp",
                        System.getProperty("java.class.path"),
                        ProcessToolFixture.class.getName(),
                        "secret"),
                Path.of("."),
                Map.of(),
                Duration.ofSeconds(5),
                4096,
                ToolCapability.LOCAL_PROCESS,
                "local");
    }

    private static ProcessToolRequest requestWithArguments(String operationId, List<String> arguments) {
        Path java = Path.of(
                System.getProperty("java.home"),
                "bin",
                System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java");
        return new ProcessToolRequest(
                operationId,
                java,
                arguments,
                Path.of("."),
                Map.of(),
                Duration.ofSeconds(5),
                4096,
                ToolCapability.LOCAL_PROCESS,
                "local");
    }

    private static final class TestSandbox implements ProcessSandbox {
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
            ArrayList<String> command = new ArrayList<>();
            command.add(specification.executable().toString());
            command.addAll(specification.arguments());
            return SandboxLaunch.direct(command);
        }
    }
}
