package io.ohmyluke.preset;

import static org.junit.jupiter.api.Assertions.*;
import static io.ohmyluke.preset.WorkflowSpecTest.*;
import io.ohmyluke.ai.*;
import io.ohmyluke.policy.ToolPermissionDecision;
import io.ohmyluke.tool.UnavailableProcessSandbox;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkflowRunServiceTest {
    @TempDir Path project;

    @Test void satisfiedPrecheckSkipsAiAndBothBranchesJoinAtFinalCheck() throws Exception {
        for (boolean ready : List.of(true, false)) {
            Files.writeString(project.resolve("hello.txt"), ready ? "ready" : "old");
            ScriptedAi ai = new ScriptedAi("ready");
            WorkflowRunService runs = service(ai);
            String id = ready ? "skip" : "edit";
            runs.start(id, spec(List.of(check("check", "final", "edit"), edit("edit", "final", "stopped"), check("final", "succeeded", "stopped"))));
            assertEquals(WorkflowStatus.SUCCEEDED, runs.resume(id).status());
            assertEquals(ready ? 0 : 1, ai.requests.size());
            assertEquals(WorkflowStatus.SUCCEEDED, service(ai).resume(id).status());
            assertEquals(ready ? 0 : 1, ai.requests.size());
        }
    }

    @Test void failedEditRetriesLocallyWithoutRepeatingPrecheck() throws Exception {
        Files.writeString(project.resolve("hello.txt"), "old");
        ScriptedAi ai = new ScriptedAi("wrong", "ready");
        WorkflowRunService runs = service(ai);
        runs.start("retry", spec(List.of(check("check", "succeeded", "edit"), edit("edit", "succeeded", "stopped"))));
        assertEquals(WorkflowStatus.SUCCEEDED, runs.resume("retry").status());
        assertEquals(2, ai.requests.size());
        assertEquals("wrong", ai.requests.get(1).context().get("currentContent"));
        var saved = new io.ohmyluke.state.CheckpointStore(project, new io.ohmyluke.state.CheckpointCodec()).load("retry").checkpoint();
        assertEquals(1, saved.state().path().stream().filter(node -> node.value().equals("check")).count());
    }

    @Test void approvalSurvivesRestartAndDoesNotRecallWriterOrApplyBeforeDecision() throws Exception {
        Files.writeString(project.resolve("hello.txt"), "old");
        ScriptedAi ai = new ScriptedAi("ready");
        WorkflowRunService runs = service(ai);
        runs.start("approval", spec(List.of(check("check", "succeeded", "edit"), WorkflowStep.edit("edit", task(), true, "succeeded", "stopped"))));
        WorkflowResult waiting = runs.resume("approval");
        assertEquals(WorkflowStatus.WAITING_APPROVAL, waiting.status());
        assertEquals("old", Files.readString(project.resolve("hello.txt")));
        assertEquals(1, ai.requests.size());
        WorkflowRunService restarted = service(ai);
        assertEquals(waiting.approval().requestId(), restarted.resume("approval").approval().requestId());
        restarted.decideApproval("approval", waiting.approval().requestId(), true);
        assertEquals("old", Files.readString(project.resolve("hello.txt")), "approval only records a decision");
        assertEquals(WorkflowStatus.SUCCEEDED, restarted.resume("approval").status());
        assertEquals(1, ai.requests.size());
    }

    @Test void changedTargetAfterApprovalWaitIsNotOverwritten() throws Exception {
        Files.writeString(project.resolve("hello.txt"), "old");
        ScriptedAi ai = new ScriptedAi("ready");
        WorkflowRunService runs = service(ai);
        runs.start("conflict", spec(List.of(check("check", "succeeded", "edit"), WorkflowStep.edit("edit", task(), true, "succeeded", "stopped"))));
        WorkflowResult waiting = runs.resume("conflict");
        Files.writeString(project.resolve("hello.txt"), "user edit");
        runs.decideApproval("conflict", waiting.approval().requestId(), true);
        assertEquals(WorkflowStatus.BLOCKED, runs.resume("conflict").status());
        assertEquals("user edit", Files.readString(project.resolve("hello.txt")));
        assertEquals(1, ai.requests.size());
    }

    @Test void deniedGateStopsAndNeverCallsAi() throws Exception {
        Files.writeString(project.resolve("hello.txt"), "old");
        ScriptedAi ai = new ScriptedAi("ready");
        WorkflowRunService runs = service(ai);
        runs.start("denied", spec(List.of(WorkflowStep.approval("check", "Continue?", "edit"), edit("edit", "succeeded", "stopped"))));
        WorkflowResult waiting = runs.resume("denied");
        assertThrows(RuntimeException.class, () -> runs.decideApproval("denied", "stale", true));
        runs.decideApproval("denied", waiting.approval().requestId(), false);
        assertEquals(WorkflowStatus.BLOCKED, runs.resume("denied").status());
        assertTrue(ai.requests.isEmpty());
    }

    WorkflowRunService service(ScriptedAi ai) {
        return new WorkflowRunService(project, task -> ai, request -> ToolPermissionDecision.allow("test.allow", "allowed", null),
                new UnavailableProcessSandbox("test unavailable"), Clock.systemUTC());
    }
    static final class ScriptedAi implements AiRuntime {
        final List<AiRequest> requests = new ArrayList<>();
        final List<String> responses;
        ScriptedAi(String... responses) { this.responses = List.of(responses); }
        public String fingerprint() { return "workflow-scripted:v1"; }
        public AiRuntimeResult invoke(AiRequest request) {
            requests.add(request);
            return AiRuntimeResult.success(PresetJson.encode(new EditProposal(request.context().get("file"), responses.get(requests.size() - 1))),
                    AiTokenUsage.measured(7, 0, 3, 0, "fixture"));
        }
    }
}
