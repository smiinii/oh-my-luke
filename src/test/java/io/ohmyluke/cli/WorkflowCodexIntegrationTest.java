package io.ohmyluke.cli;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.ohmyluke.ai.AiTokenUsage;
import io.ohmyluke.preset.*;
import io.ohmyluke.state.CheckpointCodec;
import io.ohmyluke.state.RunCheckpoint;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Opt-in real-provider checks, not a quality or token-savings benchmark. */
@EnabledOnOs({OS.MAC, OS.LINUX})
class WorkflowCodexIntegrationTest {
    private static final String READY = "OML_WORKFLOW_OK";
    private static final String OLD = "needs-change";
    private static final String RUN = "workflow-check";
    @TempDir(cleanup = CleanupMode.ON_SUCCESS) Path directory;

    @Test
    void satisfiedInputSkipsCodexEvenAcrossNewCliProcesses() throws Exception {
        var process = fixture(false, READY);
        process.cli("workflow", "workflow.json", "--run-id", RUN).expect(0,
                "result=SUCCEEDED", "aiAttempts=0", "recordedUsage=0");
        process.cli("inspect", RUN).expect(0, "result=SUCCEEDED", "aiAttempts=0");
        process.cli("resume", RUN).expect(0, "result=SUCCEEDED", "aiAttempts=0", "recordedUsage=0");
        assertEquals(READY, Files.readString(process.project.resolve("hello.txt")));
        assertEquals(0, process.execLaunches());
        assertEquals(0, writerVisits(checkpoint(process)));
        assertEquals(Map.of(), invocations(process));
    }

    @Test
    void disabledLiveFixtureRecordsAttemptButCannotForwardToRealCodex() throws Exception {
        var process = fixture(false, OLD);
        process.cli("workflow", "workflow.json", "--run-id", RUN).expect(1,
                "result=BLOCKED", "reason=ai-runtime.execution-failed", "aiAttempts=1");
        assertEquals(OLD, Files.readString(process.project.resolve("hello.txt")));
        assertEquals(1, process.execLaunches(), "the guard executable, not real Codex, was launched");
        assertEquals(Map.of(), invocations(process));
    }

    @ParameterizedTest(name = "real Codex: {0}")
    @EnumSource(Decision.class)
    @EnabledIfEnvironmentVariable(named = "OML_WORKFLOW_CODEX_INTEGRATION", matches = "true")
    void realProposalSurvivesProcessExitAndRequiresAnExplicitSafeDecision(Decision decision) throws Exception {
        var process = fixture(true, OLD);
        String version = process.requireChatGptLoginAndVersion();
        process.cli("workflow", "workflow.json", "--run-id", RUN).expect(3,
                "result=WAITING_APPROVAL", "aiAttempts=1", "approvalDecision=PENDING");
        RunCheckpoint waiting = checkpoint(process);
        assertEquals(RUN, waiting.runId());
        assertEquals("edit.approval", waiting.state().currentNode().value());
        assertEquals(1, writerVisits(waiting));
        String request = waiting.approval().requestId();
        assertEquals(OLD, Files.readString(process.project.resolve("hello.txt")));
        assertEquals(1, process.execLaunches());
        Map<String, String> saved = invocations(process);
        assertEquals(1, saved.size());
        JsonNode runtimeResult = new ObjectMapper().readTree(saved.values().iterator().next()).required("result");
        assertEquals("SUCCESS", runtimeResult.required("status").textValue());
        AiTokenUsage usage = new ObjectMapper().treeToValue(runtimeResult.required("tokenUsage"), AiTokenUsage.class);

        // The original contract may disappear: subsequent CLI invocations must restore saved state.
        Files.writeString(process.project.resolve("workflow.json"), "not valid JSON anymore");
        process.cli("inspect", RUN).expect(0, "result=WAITING_APPROVAL", "approvalRequestId=" + request);
        process.cli("resume", RUN).expect(3, "result=WAITING_APPROVAL", "approvalRequestId=" + request);
        assertEquals(waiting.state(), checkpoint(process).state());
        assertEquals(OLD, Files.readString(process.project.resolve("hello.txt")));

        String preserved = decision == Decision.CONFLICT ? "external-user-change" : OLD;
        if (decision == Decision.CONFLICT) { Files.writeString(process.project.resolve("hello.txt"), preserved); }
        process.cli(decision == Decision.DENY ? "deny" : "approve", RUN, request).expect(0,
                "approvalDecision=" + (decision == Decision.DENY ? "DENIED" : "APPROVED"));
        assertEquals(preserved, Files.readString(process.project.resolve("hello.txt")));
        assertEquals(waiting.state(), checkpoint(process).state(), "decision must not execute a node");
        int code = decision == Decision.APPROVE ? 0 : 1;
        String status = decision == Decision.APPROVE ? "SUCCEEDED" : "BLOCKED";
        String reason = switch (decision) {
            case APPROVE -> "validation-passed";
            case DENY -> "approval-denied";
            case CONFLICT -> "file-apply-blocked-or-conflict";
        };
        process.cli("resume", RUN).expect(code, "result=" + status, "reason=" + reason, "aiAttempts=1");
        String expected = decision == Decision.APPROVE ? READY : preserved;
        assertEquals(expected, Files.readString(process.project.resolve("hello.txt")));
        RunCheckpoint completed = checkpoint(process);
        process.cli("inspect", RUN).expect(0, "result=" + status, "aiAttempts=1");
        process.cli("resume", RUN).expect(code, "result=" + status, "aiAttempts=1");
        assertEquals(completed.state(), checkpoint(process).state());
        assertEquals(expected, Files.readString(process.project.resolve("hello.txt")));
        assertEquals(1, writerVisits(completed));
        assertEquals(1, process.execLaunches(), "resume must not launch Codex again");
        assertEquals(saved, invocations(process), "saved runtime result must remain byte-identical");
        assertEquals(waiting.policyState().usage(), completed.policyState().usage());
        // Emit safe measurements before strict telemetry assertions so an unavailable report is visible.
        System.out.println("WORKFLOW_EVIDENCE " + PresetJson.encode(Map.of(
                "scenario", decision, "status", status, "reason", reason, "codexVersion", version,
                "modelAndReasoning", "inherit (effective values not observed)", "aiAttempts", 1,
                "codexExecLaunches", process.execLaunches(), "recordedUsage", completed.policyState().usage(),
                "tokenUsage", usage, "javaVersion", System.getProperty("java.version"))));
        assertTrue(usage.available(), "Workflow worked but provider token breakdown was unavailable; do not infer zero usage");
        assertTrue(usage.recordedTotal() > 0);
        assertEquals(usage.recordedTotal(), completed.policyState().usage());
        process.cli("inspect", RUN).expect(0, "allTokenUsageAvailable=true", "recordedUsage=" + usage.recordedTotal());
    }

    private WorkflowCliProcess fixture(boolean live, String initial) throws Exception {
        var process = new WorkflowCliProcess(directory, live);
        Files.writeString(process.project.resolve("hello.txt"), initial);
        ValidationSpec validation = new ValidationSpec(List.of(READY), List.of(OLD), null);
        var task = new TaskSpec(1, "Replace hello.txt with exactly OML_WORKFLOW_OK (no newline). Return only path/content JSON.",
                "hello.txt", ExecutionMode.DIRECT, 1, 0, 300_000, 1, validation, null, null);
        var spec = new WorkflowSpec(1, "Verify a bounded single-file edit with explicit approval", "check", List.of(
                WorkflowStep.check("check", "hello.txt", validation, "finalCheck", "edit"),
                WorkflowStep.edit("edit", task, true, "finalCheck", "stopped"),
                WorkflowStep.check("finalCheck", "hello.txt", validation, "succeeded", "stopped")), 30, 0, 300_000);
        Files.writeString(process.project.resolve("workflow.json"), PresetJson.encode(spec));
        return process;
    }

    private static RunCheckpoint checkpoint(WorkflowCliProcess process) throws Exception {
        return new CheckpointCodec().decode(Files.readString(process.project.resolve(".oml/runs/" + RUN + "/state.json")));
    }

    private static long writerVisits(RunCheckpoint checkpoint) {
        return checkpoint.state().events().stream().filter(event -> event.node().value().equals("edit.writer")).count();
    }

    private static Map<String, String> invocations(WorkflowCliProcess process) throws Exception {
        Path root = process.project.resolve(".oml/runtime/codex/invocations");
        Map<String, String> results = new LinkedHashMap<>();
        if (Files.notExists(root)) { return results; }
        try (var files = Files.list(root)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".json")).sorted().toList()) {
                results.put(file.getFileName().toString(), Files.readString(file));
            }
        }
        return results;
    }

    enum Decision { APPROVE, DENY, CONFLICT }
}
