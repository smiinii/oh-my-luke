package io.ohmyluke.preset;

import static org.junit.jupiter.api.Assertions.*;
import static io.ohmyluke.preset.WorkflowSpecTest.*;
import io.ohmyluke.ai.*;
import io.ohmyluke.graph.*;
import io.ohmyluke.policy.*;
import io.ohmyluke.state.*;
import io.ohmyluke.tool.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkflowSafetyTest {
    @TempDir Path project;
    final AtomicInteger calls = new AtomicInteger();

    @Test void blockedCheckCannotTakeItsFalseBranchOrCallAi() throws Exception {
        Files.writeString(project.resolve("hello.txt"), "old");
        var runs = service(request -> ToolPermissionDecision.deny("test.deny", "denied"), Clock.systemUTC(), false);
        runs.start("blocked", spec(List.of(check("check", "succeeded", "edit"), edit("edit", "succeeded", "stopped"))));
        assertEquals(WorkflowStatus.BLOCKED, runs.resume("blocked").status());
        assertEquals(0, calls.get());
    }

    @Test void approvalDoesNotGrantWritePermission() throws Exception {
        Files.writeString(project.resolve("hello.txt"), "old");
        ToolPermissionEvaluator permissions = request -> request.capability() == ToolCapability.PROJECT_READ
                ? ToolPermissionDecision.allow("test.read", "read", null) : ToolPermissionDecision.deny("test.write-denied", "denied");
        var runs = service(permissions, Clock.systemUTC(), false);
        runs.start("deny-write", spec(List.of(check("check", "succeeded", "edit"), WorkflowStep.edit("edit", task(), true, "succeeded", "stopped"))));
        var waiting = runs.resume("deny-write");
        assertEquals(WorkflowStatus.WAITING_APPROVAL, waiting.status());
        runs.decideApproval("deny-write", waiting.approval().requestId(), true);
        assertEquals(WorkflowStatus.BLOCKED, runs.resume("deny-write").status());
        assertEquals("old", Files.readString(project.resolve("hello.txt")));
    }

    @Test void sharedUsageStopsBeforeApplyAndUnknownTokensAreNotZero() throws Exception {
        for (boolean unknown : List.of(false, true)) {
            Files.writeString(project.resolve("hello.txt"), "old");
            TaskSpec task = new TaskSpec(1, "Make ready", "hello.txt", ExecutionMode.DIRECT, 1, 10, 60_000, 2, validation(), null, null);
            var spec = new WorkflowSpec(1, "Make ready", "edit", List.of(WorkflowStep.edit("edit", task, false, "succeeded", "stopped")), 100, 10, 60_000);
            var runs = service(allow(), Clock.systemUTC(), unknown);
            String id = unknown ? "unknown" : "usage";
            runs.start(id, spec);
            var result = runs.resume(id);
            assertEquals(unknown ? WorkflowStatus.BLOCKED : WorkflowStatus.LIMIT_REACHED, result.status());
            assertEquals(!unknown, result.allTokenUsageAvailable());
            assertEquals("old", Files.readString(project.resolve("hello.txt")));
        }
    }

    @Test void stepLimitAndElapsedLimitPreventMoreCallsAndSurviveRestart() throws Exception {
        Files.writeString(project.resolve("hello.txt"), "old");
        var normal = spec(List.of(check("check", "succeeded", "edit"), edit("edit", "succeeded", "stopped")));
        var runs = service(allow(), Clock.fixed(Instant.ofEpochMilli(1_000), ZoneOffset.UTC), false);
        runs.start("steps", new WorkflowSpec(1, normal.goal(), normal.start(), normal.steps(), 1, 0, 60_000));
        assertEquals(WorkflowStatus.LIMIT_REACHED, runs.resume("steps").status());
        runs.start("elapsed", normal);
        assertEquals(WorkflowStatus.LIMIT_REACHED,
                service(allow(), Clock.fixed(Instant.ofEpochMilli(70_000), ZoneOffset.UTC), false).resume("elapsed").status());
        assertEquals(0, calls.get());
    }

    @Test void failedValidationCanTakeAnExplicitFallbackWithoutRestartingEarlierWork() throws Exception {
        Files.writeString(project.resolve("hello.txt"), "old");
        TaskSpec fails = new TaskSpec(1, "Write ready", "hello.txt", ExecutionMode.DIRECT, 1, 0, 60_000, 2,
                new ValidationSpec(List.of("different"), List.of(), null), null, null);
        var runs = service(allow(), Clock.systemUTC(), false);
        runs.start("fallback", new WorkflowSpec(1, "Accept ready", "edit", List.of(
                WorkflowStep.edit("edit", fails, false, "final", "final"), check("final", "succeeded", "stopped")), 100, 0, 60_000));
        assertEquals(WorkflowStatus.SUCCEEDED, runs.resume("fallback").status());
        assertEquals(1, calls.get());
    }

    @Test void unavailableCommandCheckerStopsInsteadOfChoosingFalseBranch() throws Exception {
        Files.writeString(project.resolve("hello.txt"), "old");
        var command = new ValidationCommand(Path.of(System.getProperty("java.home"), "bin", "java").toString(), List.of("-version"), 0, 1_000);
        var runs = service(allow(), Clock.systemUTC(), false);
        runs.start("command", spec(List.of(WorkflowStep.check("check", "hello.txt", new ValidationSpec(List.of(), List.of(), command), "succeeded", "edit"), edit("edit", "succeeded", "stopped"))));
        assertEquals(WorkflowStatus.BLOCKED, runs.resume("command").status());
        assertEquals(0, calls.get());
    }

    @Test void changedDefinitionCannotResumeAndContractCannotEditItself() throws Exception {
        Files.writeString(project.resolve("hello.txt"), "old");
        var runs = service(allow(), Clock.systemUTC(), false);
        var spec = spec(List.of(check("check", "succeeded", "edit"), edit("edit", "succeeded", "stopped")));
        runs.start("changed", spec);
        CheckpointStore store = new CheckpointStore(project, new CheckpointCodec());
        var checkpoint = store.load("changed").checkpoint();
        var values = new java.util.LinkedHashMap<>(checkpoint.state().values());
        values.put("workflow.spec", PresetJson.encode(new WorkflowSpec(1, "Changed goal", spec.start(), spec.steps(), 100, 0, 60_000)));
        var state = checkpoint.state();
        store.save(RunCheckpoint.current("changed", checkpoint.graphSignature(), checkpoint.phase(),
                new RunState(state.status(), state.currentNode(), state.executedSteps(), values, state.path(), state.events()),
                checkpoint.policyConfiguration(), checkpoint.policyState()));
        assertThrows(RuntimeException.class, () -> runs.resume("changed"));
        Files.writeString(project.resolve("hello.txt"), PresetJson.encode(spec));
        assertThrows(IllegalArgumentException.class, () -> runs.readSpec(Path.of("hello.txt")));
        assertEquals(0, calls.get());
    }

    private ToolPermissionEvaluator allow() { return request -> ToolPermissionDecision.allow("test.allow", "allowed", null); }
    private WorkflowRunService service(ToolPermissionEvaluator permissions, Clock clock, boolean unknown) {
        return new WorkflowRunService(project, task -> new AiRuntime() {
            public String fingerprint() { return "workflow-safety:v1"; }
            public AiRuntimeResult invoke(AiRequest request) {
                calls.incrementAndGet();
                String response = PresetJson.encode(new EditProposal(request.context().get("file"), "ready"));
                return unknown ? AiRuntimeResult.success(response, AiTokenUsage.unavailable())
                        : AiRuntimeResult.success(response, AiTokenUsage.measured(7, 0, 3, 0, "fixture"));
            }
        }, permissions, new UnavailableProcessSandbox("fixture unavailable"), clock);
    }
}
