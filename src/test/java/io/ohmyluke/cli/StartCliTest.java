package io.ohmyluke.cli;

import static org.junit.jupiter.api.Assertions.*;

import io.ohmyluke.ai.*;
import io.ohmyluke.graph.*;
import io.ohmyluke.preset.*;
import io.ohmyluke.runtime.ManagedRunService;
import io.ohmyluke.state.*;
import io.ohmyluke.tool.UnavailableProcessSandbox;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StartCliTest {
    @TempDir Path project;
    private final ByteArrayOutputStream output = new ByteArrayOutputStream();
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicReference<TaskSpec> actualTask = new AtomicReference<>();
    private boolean failFirst;

    @BeforeEach void initialFile() throws Exception { Files.writeString(project.resolve("hello.txt"), "old"); }

    @Test void interactiveAutoDoesNotStartUntilTheUserChoosesAndRetriesOnlyWithinTheBudget() throws Exception {
        writeTask(3, false);
        failFirst = true;
        var cli = fixture(StartPrompt.interactive(() -> {
            assertEquals(0, calls.get());
            assertFalse(new CheckpointStore(project, new CheckpointCodec()).exists("chosen"));
            return "1";
        })).cli();
        assertEquals(0, cli.execute(new String[] {"start", "job.json", "--run-id", "chosen"}));
        assertEquals(2, calls.get());
        assertEquals(ExecutionMode.LOOP, actualTask.get().mode());
        assertOutput("selectionStrategy=AUTO", "mode=LOOP", "selectionReason=auto-bounded-retry", "selectionRuleVersion=1");
        assertEquals("ready", Files.readString(project.resolve("hello.txt")));
    }

    @Test void manualDirectOverridesRetryAllowanceWithoutIncreasingAnyOtherBudget() throws Exception {
        writeTask(3, false);
        Queue<String> input = new ArrayDeque<>(List.of("2", "1"));
        assertEquals(0, fixture(StartPrompt.interactive(() -> {
            assertEquals(0, calls.get());
            assertFalse(new CheckpointStore(project, new CheckpointCodec()).exists("manual"));
            return input.poll();
        })).cli().execute(new String[] {"start", "job.json", "--run-id", "manual",
                "--model", "chosen-model", "--reasoning", "low"}));
        assertEquals(1, calls.get());
        assertEquals(ExecutionMode.DIRECT, actualTask.get().mode());
        assertEquals(1, actualTask.get().maxAttempts());
        assertEquals(1_000, actualTask.get().maxUsage());
        assertEquals(60_000, actualTask.get().maxElapsedMillis());
        assertEquals("chosen-model", actualTask.get().model());
        assertEquals("low", actualTask.get().reasoning());
        assertOutput("selectionStrategy=MANUAL", "mode=DIRECT", "selectionReason=manual-direct");
    }

    @Test void explicitAutoNeverPromptsAndPreservesApprovalAcrossRestartAndContractReplacement() throws Exception {
        writeTask(3, true);
        Fixture first = fixture(forbiddenPrompt());
        var before = first.permissions().settings();
        assertEquals(3, first.cli().execute(new String[] {"start", "job.json", "--mode", "auto", "--run-id", "approval"}));
        assertOutput("mode=WORKFLOW", "selectionReason=auto-approval-required", "result=WAITING_APPROVAL");
        assertEquals("old", Files.readString(project.resolve("hello.txt")));
        String request = first.workflows().inspect("approval").approval().requestId();
        String saved = first.runs().inspect("approval").state().values().get(RunSelection.STATE_KEY);
        assertNotNull(saved);
        Files.writeString(project.resolve("job.json"), "changed after start");
        output.reset();
        Fixture restarted = fixture(forbiddenPrompt());
        assertEquals(0, restarted.cli().execute(new String[] {"inspect", "approval"}));
        assertOutput("selectionStrategy=AUTO", "mode=WORKFLOW", "selectionReason=auto-approval-required");
        assertEquals(3, restarted.cli().execute(new String[] {"resume", "approval"}));
        assertEquals(0, restarted.cli().execute(new String[] {"approve", "approval", request}));
        assertEquals("old", Files.readString(project.resolve("hello.txt")));
        assertEquals(0, restarted.cli().execute(new String[] {"resume", "approval"}));
        assertEquals(1, calls.get());
        assertEquals("ready", Files.readString(project.resolve("hello.txt")));
        assertEquals(saved, restarted.runs().inspect("approval").state().values().get(RunSelection.STATE_KEY));
        assertEquals(before, restarted.permissions().settings(), "mode selection is not autonomous permission approval");
    }

    @Test void manualWorkflowWorksForASimpleTaskAndDoesNotAddAnUnrequestedApproval() throws Exception {
        writeTask(1, false);
        assertEquals(0, fixture(forbiddenPrompt()).cli().execute(new String[] {
                "start", "job.json", "--mode", "workflow", "--run-id", "manual-workflow"}));
        assertEquals(1, calls.get());
        assertOutput("mode=WORKFLOW", "selectionStrategy=MANUAL", "result=SUCCEEDED");
        assertFalse(output.toString(StandardCharsets.UTF_8).contains("approvalRequestId="));
    }

    @Test void manualModeCannotDiscardApprovalOrADeclaredGraph() throws Exception {
        writeTask(3, true);
        OmlukeCli cli = fixture(forbiddenPrompt()).cli();
        assertEquals(1, cli.execute(new String[] {"start", "job.json", "--mode", "direct", "--run-id", "unsafe"}));
        assertEquals(1, cli.execute(new String[] {"start", "job.json", "--mode", "loop", "--run-id", "unsafe"}));
        writeWorkflow();
        assertEquals(1, cli.execute(new String[] {"start", "job.json", "--mode", "direct", "--run-id", "unsafe"}));
        assertEquals(0, calls.get());
        assertFalse(new CheckpointStore(project, new CheckpointCodec()).exists("unsafe"));
        assertEquals("old", Files.readString(project.resolve("hello.txt")));
    }

    @Test void declaredGraphCanBeAutomaticallySelectedAndCompletedWithoutAi() throws Exception {
        Files.writeString(project.resolve("hello.txt"), "ready");
        writeWorkflow();
        assertEquals(0, fixture(forbiddenPrompt()).cli().execute(new String[] {
                "start", "job.json", "--mode", "auto", "--run-id", "check-only"}));
        assertEquals(0, calls.get());
        assertOutput("mode=WORKFLOW", "selectionReason=auto-workflow-declared", "result=SUCCEEDED", "recordedUsage=0");
    }

    @Test void cancellationAndEofLeaveNoRunAndNoAiCall() throws Exception {
        writeTask(3, false);
        for (StartPrompt prompt : List.of(StartPrompt.interactive(() -> "0"), StartPrompt.interactive(() -> null))) {
            assertEquals(130, fixture(prompt).cli().execute(new String[] {"start", "job.json", "--run-id", "cancelled"}));
            assertFalse(new CheckpointStore(project, new CheckpointCodec()).exists("cancelled"));
        }
        assertEquals(0, calls.get());
        assertEquals("old", Files.readString(project.resolve("hello.txt")));
    }

    @Test void nonInteractiveAndMalformedOptionsFailWithoutStartingAnything() throws Exception {
        writeTask(3, false);
        OmlukeCli cli = fixture(StartPrompt.unavailable()).cli();
        assertEquals(2, cli.execute(new String[] {"start", "job.json", "--run-id", "missing-choice"}));
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("--mode"));
        assertEquals(2, cli.execute(new String[] {"start", "job.json", "--mode"}));
        assertEquals(2, cli.execute(new String[] {"start", "job.json", "--mode", "auto", "--mode", "loop"}));
        assertEquals(2, cli.execute(new String[] {"start", "job.json", "--mode", "manual"}));
        assertEquals(2, cli.execute(new String[] {"start", "job.json", "--unsafe", "true"}));
        assertEquals(0, calls.get());
        assertFalse(new CheckpointStore(project, new CheckpointCodec()).exists("missing-choice"));
        assertEquals(0, cli.execute(new String[] {"start", "job.json", "--mode", "loop", "--run-id", "explicit"}));
        assertEquals(1, calls.get());
    }

    @Test void invalidInteractiveAnswersCannotDefaultToExecution() throws Exception {
        writeTask(3, false);
        assertEquals(2, fixture(StartPrompt.interactive(() -> "wrong"))
                .cli().execute(new String[] {"start", "job.json", "--run-id", "invalid"}));
        assertEquals(0, calls.get());
        assertFalse(new CheckpointStore(project, new CheckpointCodec()).exists("invalid"));
    }

    @Test void legacyManualCommandsNeverAskForAStartSelection() throws Exception {
        TaskSpec task = new TaskSpec(1, "Make ready", "hello.txt", ExecutionMode.DIRECT, 1, 0, 60_000, 2,
                validation(), null, null);
        Files.writeString(project.resolve("task.json"), PresetJson.encode(task));
        Fixture fixture = fixture(forbiddenPrompt());
        assertEquals(0, fixture.cli().execute(new String[] {"run", "task.json", "--run-id", "legacy"}));
        assertEquals(1, calls.get());
        assertFalse(fixture.runs().inspect("legacy").state().values().containsKey(RunSelection.STATE_KEY));
    }

    private Fixture fixture(StartPrompt prompt) {
        Clock clock = Clock.systemUTC();
        var permissions = new ProjectPermissionManager(new ProjectPermissionStore(project), clock);
        var runs = new ManagedRunService(new GraphRunner(new GraphValidator()),
                new CheckpointStore(project, new CheckpointCodec()), new EventLogStore(project, new RunEventCodec()),
                new HandoffStore(project), new RunLockManager(project));
        Function<TaskSpec, AiRuntime> runtime = task -> {
            actualTask.set(task);
            return new AiRuntime() {
                @Override public String fingerprint() { return "start-cli-fixture:" + task.model() + ":" + task.reasoning(); }
                @Override public AiRuntimeResult invoke(AiRequest request) {
                    int call = calls.incrementAndGet();
                    String content = failFirst && call == 1 ? "not-yet" : "ready";
                    return AiRuntimeResult.success("{\"path\":\"hello.txt\",\"content\":\"" + content + "\"}",
                            AiTokenUsage.measured(7, 0, 3, 0, "fixture"));
                }
            };
        };
        var sandbox = new UnavailableProcessSandbox("fixture");
        var presets = new PresetRunService(project, runtime, permissions, sandbox, clock);
        var workflows = new WorkflowRunService(project, runtime, permissions, sandbox, clock);
        var starts = new StartRunService(project, presets, workflows, permissions, clock);
        var out = new PrintStream(output, true, StandardCharsets.UTF_8);
        return new Fixture(new OmlukeCli(runs, GraphResolver.none(), permissions, out, out, presets, workflows, starts, prompt),
                runs, workflows, permissions);
    }

    private void writeTask(int attempts, boolean approval) throws Exception {
        var task = new StartTaskSpec("Make ready", "hello.txt", attempts, 1_000, 60_000, 2,
                validation(), "file-model", "medium", approval);
        Files.writeString(project.resolve("job.json"), PresetJson.encode(new StartSpec(1, task, null)));
    }

    private void writeWorkflow() throws Exception {
        var workflow = new WorkflowSpec(1, "Check ready", "check", List.of(
                WorkflowStep.check("check", "hello.txt", validation(), "succeeded", "stopped")), 20, 0, 60_000);
        Files.writeString(project.resolve("job.json"), PresetJson.encode(new StartSpec(1, null, workflow)));
    }

    private void assertOutput(String... lines) {
        String text = output.toString(StandardCharsets.UTF_8);
        for (String line : lines) { assertTrue(text.lines().anyMatch(line::equals), text); }
    }

    private static StartPrompt forbiddenPrompt() {
        return StartPrompt.interactive(() -> { throw new AssertionError("unexpected question"); });
    }

    private static ValidationSpec validation() { return new ValidationSpec(List.of("ready"), List.of("old"), null); }
    private record Fixture(OmlukeCli cli, ManagedRunService runs, WorkflowRunService workflows,
                           ProjectPermissionManager permissions) {}
}
