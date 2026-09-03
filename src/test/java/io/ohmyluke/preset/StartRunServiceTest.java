package io.ohmyluke.preset;

import static org.junit.jupiter.api.Assertions.*;
import static io.ohmyluke.preset.StartSpecTest.*;

import io.ohmyluke.ai.*;
import io.ohmyluke.policy.ToolCapability;
import io.ohmyluke.policy.ToolPermissionDecision;
import io.ohmyluke.policy.ToolPermissionEvaluator;
import io.ohmyluke.state.CheckpointCodec;
import io.ohmyluke.state.CheckpointStore;
import io.ohmyluke.tool.UnavailableProcessSandbox;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StartRunServiceTest {
    @TempDir Path project;
    private final Clock clock = Clock.systemUTC();
    private final ToolPermissionEvaluator allow = request -> ToolPermissionDecision.allow("test.allow", "allowed", null);

    @Test void readingAndSelectingCannotRunAiExecuteNodesOrMutateTarget() throws Exception {
        Files.writeString(project.resolve("hello.txt"), "old");
        StartSpec input = new StartSpec(1, task(3, false), null);
        Files.writeString(project.resolve("start.json"), PresetJson.encode(input));
        ScriptedAi ai = new ScriptedAi("ready");
        StartRunService service = service(ai, allow);
        assertEquals(input, service.readSpec(Path.of("start.json")));
        StartPlan plan = StartModeSelector.select(input, StartChoice.AUTO);
        assertTrue(ai.requests.isEmpty());
        assertFalse(Files.exists(project.resolve(".oml")));
        service.start("new-start", plan);
        assertTrue(ai.requests.isEmpty());
        assertEquals("old", Files.readString(project.resolve("hello.txt")));
        assertEquals(0, checkpoints().load("new-start").checkpoint().state().executedSteps());
        assertEquals(plan.selection(), savedSelection("new-start"));
    }

    @Test void savedManualSelectionAndEffectiveContractSurviveRestartWithoutReselection() throws Exception {
        Files.writeString(project.resolve("hello.txt"), "old");
        ScriptedAi ai = new ScriptedAi("wrong", "ready");
        StartSpec input = new StartSpec(1, task(3, false), null);
        StartPlan plan = StartModeSelector.select(input, StartChoice.DIRECT);
        service(ai, allow).start("manual", plan);
        PresetRunService restarted = presets(ai, allow);
        assertEquals(PresetStatus.RUNNING, restarted.inspect("manual").status());
        assertEquals(PresetStatus.VALIDATION_FAILED, restarted.resume("manual").status());
        assertEquals(1, ai.requests.size(), "resume must keep manual DIRECT despite the original retry budget");
        assertEquals(plan.selection(), savedSelection("manual"));
        assertEquals(plan.task(), PresetJson.decode(values("manual").get(PresetGraph.TASK), TaskSpec.class));
        assertFalse(ai.requests.getFirst().context().containsKey(RunSelection.STATE_KEY));
        assertFalse(ai.requests.getFirst().context().values().stream().anyMatch(value -> value.contains("manual-direct")));
    }

    @Test void automaticLoopAndWorkflowSelectionRemainFixedAcrossNewServices() throws Exception {
        for (StartChoice choice : List.of(StartChoice.AUTO, StartChoice.WORKFLOW)) {
            Files.writeString(project.resolve("hello.txt"), "old");
            ScriptedAi ai = new ScriptedAi("wrong", "ready");
            StartPlan plan = StartModeSelector.select(new StartSpec(1, task(3, false), null), choice);
            String id = "restart-" + choice.name().toLowerCase(java.util.Locale.ROOT);
            service(ai, allow).start(id, plan);
            if (plan.task() != null) {
                presets(ai, allow).step(id);
                assertEquals(PresetStatus.SUCCEEDED, presets(ai, allow).resume(id).status());
            } else {
                workflows(ai, allow).step(id);
                assertEquals(WorkflowStatus.SUCCEEDED, workflows(ai, allow).resume(id).status());
                assertEquals(plan.workflow(), PresetJson.decode(values(id).get("workflow.spec"), WorkflowSpec.class));
            }
            assertEquals(2, ai.requests.size());
            assertEquals(plan.selection(), savedSelection(id));
            assertTrue(ai.requests.stream().noneMatch(request -> request.context().containsKey(RunSelection.STATE_KEY)));
        }
    }

    @Test void workflowApprovalSurvivesRestartAndNeverGrantsWritePermission() throws Exception {
        Files.writeString(project.resolve("hello.txt"), "old");
        ScriptedAi ai = new ScriptedAi("ready");
        ToolPermissionEvaluator readOnly = request -> request.capability() == ToolCapability.PROJECT_READ
                ? ToolPermissionDecision.allow("test.read", "read allowed", null)
                : ToolPermissionDecision.ask("test.write", "write needs permission");
        StartPlan plan = StartModeSelector.select(new StartSpec(1, task(3, true), null), StartChoice.AUTO);
        service(ai, readOnly).start("approval", plan);
        WorkflowResult waiting = workflows(ai, readOnly).resume("approval");
        assertEquals(WorkflowStatus.WAITING_APPROVAL, waiting.status());
        assertEquals("old", Files.readString(project.resolve("hello.txt")));
        WorkflowRunService restarted = workflows(ai, readOnly);
        assertEquals(waiting.approval(), restarted.resume("approval").approval());
        assertEquals(plan.selection(), savedSelection("approval"));
        restarted.decideApproval("approval", waiting.approval().requestId(), true);
        assertEquals(WorkflowStatus.BLOCKED, restarted.resume("approval").status());
        assertEquals(1, ai.requests.size());
        assertEquals("old", Files.readString(project.resolve("hello.txt")));
        assertEquals(plan.selection(), savedSelection("approval"));
    }

    @Test void inputUsesExistingPermissionAndPathBoundariesAndCannotEditItsContract() throws Exception {
        ScriptedAi ai = new ScriptedAi("ready");
        StartSpec input = new StartSpec(1, task(3, false), null);
        Files.writeString(project.resolve("start.json"), PresetJson.encode(input));
        StartRunService service = service(ai, allow);
        for (Path path : List.of(Path.of("../start.json"), Path.of(".oml/start.json"),
                Path.of(".git/config"), Path.of(".env"), project.resolve("start.json"))) {
            assertThrows(IllegalArgumentException.class, () -> service.readSpec(path));
        }
        Files.createSymbolicLink(project.resolve("linked.json"), project.resolve("start.json"));
        assertThrows(IllegalArgumentException.class, () -> service.readSpec(Path.of("linked.json")));
        assertThrows(IllegalArgumentException.class, () -> service(ai,
                request -> ToolPermissionDecision.deny("test.deny", "not allowed")).readSpec(Path.of("start.json")));
        Files.writeString(project.resolve("hello.txt"), PresetJson.encode(input));
        assertThrows(IllegalArgumentException.class, () -> service.readSpec(Path.of("hello.txt")));
        Files.writeString(project.resolve("hello.txt"), PresetJson.encode(new StartSpec(1, null, workflow())));
        assertThrows(IllegalArgumentException.class, () -> service.readSpec(Path.of("hello.txt")));
        Files.writeString(project.resolve("too-large.json"), " ".repeat(512 * 1024 + 1));
        assertThrows(IllegalArgumentException.class, () -> service.readSpec(Path.of("too-large.json")));
        assertTrue(ai.requests.isEmpty());
        assertFalse(Files.exists(project.resolve(".oml")));
    }

    @Test void documentedExamplesReadAndSelectWithoutRuntimeEffects() {
        Path examples = Path.of("examples/start").toAbsolutePath();
        ScriptedAi ai = new ScriptedAi("ready");
        StartRunService starts = new StartRunService(examples, presets(ai, allow), workflows(ai, allow), allow, clock);
        StartSpec task = starts.readSpec(Path.of("task.json"));
        StartSpec approval = starts.readSpec(Path.of("approval-task.json"));
        assertEquals(RunSelection.Mode.LOOP, StartModeSelector.select(task, StartChoice.AUTO).selection().mode());
        StartPlan gated = StartModeSelector.select(approval, StartChoice.AUTO);
        assertEquals(RunSelection.Mode.WORKFLOW, gated.selection().mode());
        assertTrue(gated.workflow().steps().getFirst().approvalBeforeApply());
        assertEquals(3, gated.workflow().steps().getFirst().task().maxAttempts());
        assertTrue(ai.requests.isEmpty());
    }

    @Test void generatedWorkflowCanReachTheTwentiethAttemptWithEveryApprovalPreserved() throws Exception {
        Files.writeString(project.resolve("hello.txt"), "old");
        List<String> responses = new ArrayList<>();
        for (int attempt = 1; attempt < 20; attempt++) { responses.add("pending-" + attempt); }
        responses.add("ready");
        ScriptedAi ai = new ScriptedAi(responses.toArray(String[]::new));
        StartPlan plan = StartModeSelector.select(new StartSpec(1, task(20, true), null), StartChoice.AUTO);
        service(ai, allow).start("twenty", plan);
        for (int attempt = 1; attempt <= 20; attempt++) {
            WorkflowRunService restarted = workflows(ai, allow);
            WorkflowResult waiting = restarted.resume("twenty");
            assertEquals(WorkflowStatus.WAITING_APPROVAL, waiting.status(), "attempt " + attempt);
            assertEquals(attempt, waiting.attempts());
            restarted.decideApproval("twenty", waiting.approval().requestId(), true);
        }
        assertEquals(WorkflowStatus.SUCCEEDED, workflows(ai, allow).resume("twenty").status());
        assertEquals(20, ai.requests.size());
        assertEquals(120, checkpoints().load("twenty").checkpoint().state().executedSteps());
        assertEquals(plan.selection(), savedSelection("twenty"));
    }

    @Test void selectingWorkflowCannotTurnTheEditableFileIntoItsOwnValidator() {
        ValidationSpec validation = new ValidationSpec(List.of(), List.of(),
                new ValidationCommand(project.resolve("hello.txt").toString(), List.of(), 0, 1_000));
        StartSpec input = new StartSpec(1, new StartTaskSpec("Make ready", "hello.txt", 3,
                0, 60_000, 3, validation, null, null, false), null);
        ScriptedAi ai = new ScriptedAi("ready");
        for (StartChoice choice : StartChoice.values()) {
            String id = "validator-" + choice.name().toLowerCase(java.util.Locale.ROOT);
            assertThrows(IllegalArgumentException.class, () -> service(ai, allow).start(id, StartModeSelector.select(input, choice)));
            assertFalse(Files.exists(project.resolve(".oml/runs/" + id)));
        }
        assertTrue(ai.requests.isEmpty());
    }

    @Test void existingStartsRemainSelectionFreeAndNewOverloadsRejectMismatchedModes() throws Exception {
        Files.writeString(project.resolve("hello.txt"), "old");
        ScriptedAi ai = new ScriptedAi("ready", "ready");
        PresetRunService presets = presets(ai, allow);
        WorkflowRunService workflows = workflows(ai, allow);
        presets.start("legacy-preset", task(1, false).toTask(ExecutionMode.DIRECT));
        workflows.start("legacy-workflow", workflow());
        assertFalse(values("legacy-preset").containsKey(RunSelection.STATE_KEY));
        assertFalse(values("legacy-workflow").containsKey(RunSelection.STATE_KEY));
        assertEquals(PresetStatus.SUCCEEDED, presets(ai, allow).resume("legacy-preset").status());
        assertEquals(WorkflowStatus.SUCCEEDED, workflows(ai, allow).resume("legacy-workflow").status());
        RunSelection direct = new RunSelection(1, RunSelection.Strategy.MANUAL, RunSelection.Mode.DIRECT, "manual-direct");
        assertThrows(IllegalArgumentException.class, () -> presets.start("mismatch-task", task(3, false).toTask(ExecutionMode.LOOP), direct));
        assertThrows(IllegalArgumentException.class, () -> workflows.start("mismatch-workflow", workflow(), direct));
        assertFalse(Files.exists(project.resolve(".oml/runs/mismatch-task")));
        assertFalse(Files.exists(project.resolve(".oml/runs/mismatch-workflow")));
    }

    private StartRunService service(ScriptedAi ai, ToolPermissionEvaluator permission) {
        return new StartRunService(project, presets(ai, permission), workflows(ai, permission), permission, clock);
    }

    private PresetRunService presets(ScriptedAi ai, ToolPermissionEvaluator permission) {
        return new PresetRunService(project, task -> ai, permission, new UnavailableProcessSandbox("test unavailable"), clock);
    }

    private WorkflowRunService workflows(ScriptedAi ai, ToolPermissionEvaluator permission) {
        return new WorkflowRunService(project, task -> ai, permission, new UnavailableProcessSandbox("test unavailable"), clock);
    }

    private CheckpointStore checkpoints() { return new CheckpointStore(project, new CheckpointCodec()); }
    private Map<String, String> values(String id) { return checkpoints().load(id).checkpoint().state().values(); }
    private RunSelection savedSelection(String id) { return PresetJson.decode(values(id).get(RunSelection.STATE_KEY), RunSelection.class); }

    private static final class ScriptedAi implements AiRuntime {
        final List<AiRequest> requests = new ArrayList<>();
        final List<String> responses;
        ScriptedAi(String... responses) { this.responses = List.of(responses); }
        public String fingerprint() { return "start-scripted:v1"; }
        public AiRuntimeResult invoke(AiRequest request) {
            requests.add(request);
            return AiRuntimeResult.success(PresetJson.encode(new EditProposal(request.context().get("file"), responses.get(requests.size() - 1))),
                    AiTokenUsage.measured(7, 0, 3, 0, "fixture"));
        }
    }
}
