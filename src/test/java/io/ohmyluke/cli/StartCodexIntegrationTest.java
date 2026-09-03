package io.ohmyluke.cli;

import static org.junit.jupiter.api.Assertions.*;

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

/** Real entry-point smoke checks, not a benchmark; each opt-in scenario permits one AI attempt. */
@EnabledOnOs({OS.MAC, OS.LINUX})
class StartCodexIntegrationTest {
    private static final String READY = "OML_START_OK";
    private static final String OLD = "needs-change";
    private static final String RUN = "start-check";
    @TempDir(cleanup = CleanupMode.ON_SUCCESS) Path directory;

    @Test
    void declaredCheckPreservesAutoSelectionAcrossProcessesWithoutCodex() throws Exception {
        var process = new WorkflowCliProcess(directory, false);
        Files.writeString(process.project.resolve("hello.txt"), READY);
        var workflow = new WorkflowSpec(1, "Check without an AI edit", "check", List.of(
                WorkflowStep.check("check", "hello.txt", new ValidationSpec(List.of(READY), List.of(OLD), null),
                        "succeeded", "stopped")), 10, 0, 60_000);
        Files.writeString(process.project.resolve("job.json"), PresetJson.encode(new StartSpec(1, null, workflow)));
        process.cli("start", "job.json", "--mode", "auto", "--run-id", RUN).expect(0,
                "result=SUCCEEDED", "aiAttempts=0", "recordedUsage=0");
        String selection = checkpoint(process).state().values().get(RunSelection.STATE_KEY);
        assertEquals(new RunSelection(1, RunSelection.Strategy.AUTO, RunSelection.Mode.WORKFLOW, "auto-workflow-declared"),
                PresetJson.decode(selection, RunSelection.class));
        Files.writeString(process.project.resolve("job.json"), "invalid after start");
        for (String command : List.of("inspect", "resume")) {
            process.cli(command, RUN).expect(0, "result=SUCCEEDED", "aiAttempts=0", "recordedUsage=0",
                    "selectionStrategy=AUTO", "mode=WORKFLOW", "selectionReason=auto-workflow-declared", "selectionRuleVersion=1");
        }
        assertEquals(selection, checkpoint(process).state().values().get(RunSelection.STATE_KEY));
        assertEquals(READY, Files.readString(process.project.resolve("hello.txt")));
        assertEquals(0, writerVisits(checkpoint(process)));
        assertEquals(0, process.execLaunches());
        assertEquals(Map.of(), invocations(process));
    }

    @ParameterizedTest
    @EnumSource(Scenario.class)
    void disabledLiveFixtureBlocksRealCodexAndCannotBeRetriedByResume(Scenario scenario) throws Exception {
        var process = fixture(false, scenario, OLD);
        start(process, scenario).expect(1, "result=BLOCKED", "reason=ai-runtime.execution-failed", "aiAttempts=1");
        assertSelection(checkpoint(process), scenario);
        expectSelection(process.cli("resume", RUN), scenario, 1, "BLOCKED");
        assertEquals(OLD, Files.readString(process.project.resolve("hello.txt")));
        assertEquals(1, process.execLaunches(), "only the non-delegating guard may execute");
        assertEquals(1, writerVisits(checkpoint(process)));
        assertEquals(Map.of(), invocations(process));
    }

    @ParameterizedTest(name = "real Codex start: {0}")
    @EnumSource(Scenario.class)
    @EnabledIfEnvironmentVariable(named = "OML_START_CODEX_INTEGRATION", matches = "true")
    void realStartKeepsSelectionAndUsageWithoutAnotherCodexLaunchOnResume(Scenario scenario) throws Exception {
        var process = fixture(true, scenario, OLD);
        String version = process.requireChatGptLoginAndVersion();
        var initial = start(process, scenario);
        expectSelection(initial, scenario, scenario.approval ? 3 : 0,
                scenario.approval ? "WAITING_APPROVAL" : "SUCCEEDED");
        initial.expect(scenario.approval ? 3 : 0, "aiAttempts=1");
        RunCheckpoint saved = checkpoint(process);
        assertSelection(saved, scenario);
        assertEquals(1, writerVisits(saved));
        assertEquals(1, process.execLaunches());
        Map<String, String> calls = invocations(process);
        assertEquals(1, calls.size());
        var mapper = new ObjectMapper();
        var result = mapper.readTree(calls.values().iterator().next()).required("result");
        assertEquals("SUCCESS", result.required("status").textValue());
        AiTokenUsage usage = mapper.treeToValue(result.required("tokenUsage"), AiTokenUsage.class);

        // Neither the original input nor a fresh selection may replace the persisted contract.
        Files.writeString(process.project.resolve("job.json"), "invalid after start");
        if (scenario.approval) {
            assertEquals("edit.approval", saved.state().currentNode().value());
            assertEquals(OLD, Files.readString(process.project.resolve("hello.txt")));
            String request = saved.approval().requestId();
            expectSelection(process.cli("inspect", RUN), scenario, 0, "WAITING_APPROVAL");
            expectSelection(process.cli("resume", RUN), scenario, 3, "WAITING_APPROVAL");
            assertEquals(saved.state(), checkpoint(process).state());
            process.cli("approve", RUN, request).expect(0, "approvalDecision=APPROVED");
            assertEquals(saved.state(), checkpoint(process).state(), "approval only records a decision");
            assertEquals(OLD, Files.readString(process.project.resolve("hello.txt")));
        }
        expectSelection(process.cli("resume", RUN), scenario, 0, "SUCCEEDED");
        assertEquals(READY, Files.readString(process.project.resolve("hello.txt")));
        RunCheckpoint completed = checkpoint(process);
        expectSelection(process.cli("inspect", RUN), scenario, 0, "SUCCEEDED");
        expectSelection(process.cli("resume", RUN), scenario, 0, "SUCCEEDED");
        assertEquals(completed.state(), checkpoint(process).state());
        assertEquals(saved.state().values().get(RunSelection.STATE_KEY), completed.state().values().get(RunSelection.STATE_KEY));
        assertEquals(saved.policyState().usage(), completed.policyState().usage());
        assertEquals(1, writerVisits(completed));
        assertEquals(1, process.execLaunches(), "inspect/approval/resume must not start another Codex exec");
        assertEquals(calls, invocations(process), "saved provider result must remain byte-identical");

        // Report an unavailable breakdown honestly before failing telemetry assertions.
        System.out.println("START_EVIDENCE " + PresetJson.encode(Map.of(
                "scenario", scenario, "status", "SUCCEEDED", "codexVersion", version,
                "modelAndReasoning", "inherit (effective values not observed)", "aiAttempts", 1,
                "codexExecLaunches", process.execLaunches(), "recordedUsage", completed.policyState().usage(),
                "tokenUsage", usage, "javaVersion", System.getProperty("java.version"))));
        assertTrue(usage.available(), "Do not interpret unavailable provider usage as zero tokens");
        assertTrue(usage.recordedTotal() > 0);
        assertEquals(usage.inputTokens() + usage.outputTokens(), usage.recordedTotal());
        assertEquals(usage.recordedTotal(), completed.policyState().usage());
        process.cli("inspect", RUN).expect(0, "aiAttempts=1", "allTokenUsageAvailable=true",
                "recordedUsage=" + usage.recordedTotal());
    }

    private WorkflowCliProcess fixture(boolean live, Scenario scenario, String initial) throws Exception {
        var process = new WorkflowCliProcess(directory, live);
        Files.writeString(process.project.resolve("hello.txt"), initial);
        var task = new StartTaskSpec("Replace hello.txt with exactly OML_START_OK (no newline). Return only path/content JSON.",
                "hello.txt", 1, 0, 300_000, 1,
                new ValidationSpec(List.of(READY), List.of(OLD), null), null, null, scenario.approval);
        Files.writeString(process.project.resolve("job.json"), PresetJson.encode(new StartSpec(1, task, null)));
        return process;
    }

    private static WorkflowCliProcess.Result start(WorkflowCliProcess process, Scenario scenario) throws Exception {
        return process.cli("start", "job.json", "--mode", scenario.choice, "--run-id", RUN);
    }

    private static void expectSelection(WorkflowCliProcess.Result result, Scenario scenario, int code, String status) {
        result.expect(code, "selectionStrategy=" + scenario.strategy, "mode=" + scenario.mode,
                "selectionReason=" + scenario.reason, "selectionRuleVersion=1", "result=" + status);
    }

    private static void assertSelection(RunCheckpoint checkpoint, Scenario scenario) {
        var selection = PresetJson.decode(checkpoint.state().values().get(RunSelection.STATE_KEY), RunSelection.class);
        assertEquals(new RunSelection(1, RunSelection.Strategy.valueOf(scenario.strategy),
                RunSelection.Mode.valueOf(scenario.mode), scenario.reason), selection);
    }

    private static RunCheckpoint checkpoint(WorkflowCliProcess process) throws Exception {
        return new CheckpointCodec().decode(Files.readString(process.project.resolve(".oml/runs/" + RUN + "/state.json")));
    }

    private static long writerVisits(RunCheckpoint checkpoint) {
        return checkpoint.state().events().stream().filter(event -> event.node().value().equals("writer")
                || event.node().value().equals("edit.writer")).count();
    }

    private static Map<String, String> invocations(WorkflowCliProcess process) throws Exception {
        Map<String, String> results = new LinkedHashMap<>();
        Path root = process.project.resolve(".oml/runtime/codex/invocations");
        if (Files.notExists(root)) { return results; }
        try (var files = Files.list(root)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".json")).sorted().toList()) {
                results.put(file.getFileName().toString(), Files.readString(file));
            }
        }
        return results;
    }

    enum Scenario {
        AUTO_DIRECT("auto", "AUTO", "DIRECT", "auto-single-attempt", false),
        MANUAL_LOOP("loop", "MANUAL", "LOOP", "manual-loop", false),
        AUTO_APPROVAL("auto", "AUTO", "WORKFLOW", "auto-approval-required", true);

        final String choice, strategy, mode, reason;
        final boolean approval;
        Scenario(String choice, String strategy, String mode, String reason, boolean approval) {
            this.choice = choice; this.strategy = strategy; this.mode = mode; this.reason = reason; this.approval = approval;
        }
    }
}
