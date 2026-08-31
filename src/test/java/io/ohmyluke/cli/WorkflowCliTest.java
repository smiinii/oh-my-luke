package io.ohmyluke.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.ohmyluke.ai.AiRequest;
import io.ohmyluke.ai.AiRuntime;
import io.ohmyluke.ai.AiRuntimeResult;
import io.ohmyluke.ai.AiTokenUsage;
import io.ohmyluke.graph.GraphRunner;
import io.ohmyluke.graph.GraphValidator;
import io.ohmyluke.preset.ExecutionMode;
import io.ohmyluke.preset.PresetJson;
import io.ohmyluke.preset.PresetRunService;
import io.ohmyluke.preset.TaskSpec;
import io.ohmyluke.preset.ValidationSpec;
import io.ohmyluke.preset.WorkflowRunService;
import io.ohmyluke.preset.WorkflowSpec;
import io.ohmyluke.preset.WorkflowStep;
import io.ohmyluke.runtime.ManagedRunService;
import io.ohmyluke.state.CheckpointCodec;
import io.ohmyluke.state.CheckpointStore;
import io.ohmyluke.state.EventLogStore;
import io.ohmyluke.state.HandoffStore;
import io.ohmyluke.state.ProjectPermissionManager;
import io.ohmyluke.state.ProjectPermissionStore;
import io.ohmyluke.state.RunEventCodec;
import io.ohmyluke.state.RunLockManager;
import io.ohmyluke.tool.UnavailableProcessSandbox;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkflowCliTest {
    @TempDir Path project;

    @Test void helpExplainsWorkflowStartAndExplicitApprovalCommands() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        OmlukeCli cli = legacyCli(output);

        assertEquals(0, cli.execute(new String[0]));

        String help = output.toString(StandardCharsets.UTF_8);
        assertTrue(help.contains("omluke workflow <workflow.json>"));
        assertTrue(help.contains("omluke <approve|deny> <run-id> <request-id>"));
    }

    @Test void legacyConstructorRejectsWorkflowCommandsWithoutAConfiguredProvider() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        OmlukeCli cli = legacyCli(output);

        assertEquals(2, cli.execute(new String[] {"workflow", "workflow.json"}));
        assertEquals(2, cli.execute(new String[] {"approve", "some-run", "some-request"}));
        assertEquals(2, cli.execute(new String[] {"deny", "some-run", "some-request"}));
    }

    @Test void approvalPersistsAcrossRestartAndDoesNotApplyUntilExplicitResume() throws Exception {
        Files.writeString(project.resolve("hello.txt"), "old");
        writeWorkflow(editWorkflow());
        var output = new ByteArrayOutputStream();
        var calls = new AtomicInteger();
        var selected = new AtomicReference<TaskSpec>();
        Fixture first = fixture(output, selected, calls);

        assertEquals(3, first.cli().execute(new String[] {"workflow", "workflow.json", "--run-id", "approved",
                "--model", "chosen-model", "--reasoning", "low"}));
        String requestId = first.workflows().inspect("approved").approval().requestId();
        assertEquals(1, calls.get());
        assertEquals("old", Files.readString(project.resolve("hello.txt")));
        assertEquals("chosen-model", selected.get().model());
        assertEquals("low", selected.get().reasoning());
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("modelOverride=chosen-model"));
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("omluke approve approved " + requestId));
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("도구 권한을 부여하지 않습니다"));
        Files.writeString(project.resolve("workflow.json"), "changed after start");

        Fixture restarted = fixture(output, selected, calls);
        var permissionsBefore = new ProjectPermissionStore(project).load();
        assertEquals(0, restarted.cli().execute(new String[] {"inspect", "approved"}));
        assertEquals(requestId, restarted.workflows().inspect("approved").approval().requestId());
        assertEquals(3, restarted.cli().execute(new String[] {"resume", "approved"}));
        assertEquals(0, restarted.cli().execute(new String[] {"approve", "approved", requestId}));
        assertEquals(permissionsBefore, new ProjectPermissionStore(project).load());
        assertEquals("old", Files.readString(project.resolve("hello.txt")));
        assertEquals(1, calls.get());
        assertEquals(1, restarted.cli().execute(new String[] {"approve", "approved", requestId}));
        assertEquals(0, restarted.cli().execute(new String[] {"resume", "approved"}));
        assertEquals("ready", Files.readString(project.resolve("hello.txt")));
        assertEquals(1, calls.get());
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("result=SUCCEEDED"));
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("재개: omluke resume approved"));
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("allTokenUsageAvailable=true"));
    }

    @Test void denialIsRecordedWithoutRunningFollowingNodes() throws Exception {
        Files.writeString(project.resolve("hello.txt"), "old");
        writeWorkflow(editWorkflow());
        var output = new ByteArrayOutputStream();
        var calls = new AtomicInteger();
        Fixture fixture = fixture(output, new AtomicReference<>(), calls);

        assertEquals(3, fixture.cli().execute(new String[] {"workflow", "workflow.json", "--run-id", "denied"}));
        String requestId = fixture.workflows().inspect("denied").approval().requestId();
        assertEquals(0, fixture.cli().execute(new String[] {"deny", "denied", requestId}));
        assertEquals("old", Files.readString(project.resolve("hello.txt")));
        assertEquals(1, fixture.cli().execute(new String[] {"resume", "denied"}));
        assertEquals("old", Files.readString(project.resolve("hello.txt")));
        assertEquals(1, calls.get());
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("approvalDecision=DENIED"));
    }

    @Test void approvalOnlyGateCanResumeIntoJavaCheckWithoutCallingAi() throws Exception {
        Files.writeString(project.resolve("hello.txt"), "ready");
        writeWorkflow(new WorkflowSpec(1, "Check after approval", "gate", List.of(
                WorkflowStep.approval("gate", "Continue with validation?", "check"),
                WorkflowStep.check("check", "hello.txt", validation(), "succeeded", "stopped")),
                30, 0, 60_000));
        var output = new ByteArrayOutputStream();
        var calls = new AtomicInteger();
        Fixture fixture = fixture(output, new AtomicReference<>(), calls);

        assertEquals(3, fixture.cli().execute(new String[] {"workflow", "workflow.json", "--run-id", "gate"}));
        assertEquals(0, calls.get());
        String requestId = fixture.workflows().inspect("gate").approval().requestId();
        assertEquals(0, fixture.cli().execute(new String[] {"approve", "gate", requestId}));
        assertEquals(0, fixture.cli().execute(new String[] {"resume", "gate"}));
        assertEquals(0, calls.get());
    }

    @Test void malformedCommandsAndWrongRequestsCannotInvokeAiOrAdvanceApproval() throws Exception {
        Files.writeString(project.resolve("hello.txt"), "old");
        writeWorkflow(editWorkflow());
        var output = new ByteArrayOutputStream();
        var calls = new AtomicInteger();
        Fixture fixture = fixture(output, new AtomicReference<>(), calls);

        assertEquals(2, fixture.cli().execute(new String[] {"workflow"}));
        assertEquals(2, fixture.cli().execute(new String[] {"workflow", "workflow.json", "--model"}));
        assertEquals(2, fixture.cli().execute(new String[] {"workflow", "workflow.json", "--model", "a", "--model", "b"}));
        assertEquals(2, fixture.cli().execute(new String[] {"workflow", "workflow.json", "--unsafe", "true"}));
        assertEquals(2, fixture.cli().execute(new String[] {"approve", "wrong"}));
        assertEquals(2, fixture.cli().execute(new String[] {"deny", "wrong"}));
        assertEquals(2, fixture.cli().execute(new String[] {"approve", "wrong", "request", "extra"}));
        assertEquals(1, fixture.cli().execute(new String[] {"approve", "missing", "request"}));
        assertEquals(0, calls.get());
        assertEquals(3, fixture.cli().execute(new String[] {"workflow", "workflow.json", "--run-id", "pending"}));
        assertEquals(1, fixture.cli().execute(new String[] {"approve", "pending", "wrong-request"}));
        assertNotNull(fixture.workflows().inspect("pending").approval());
        assertEquals(3, fixture.cli().execute(new String[] {"resume", "pending"}));
        assertEquals(1, calls.get());
        assertEquals("old", Files.readString(project.resolve("hello.txt")));
    }

    @Test void cancelPreventsApprovalAndResumeFromApplyingThePendingChange() throws Exception {
        Files.writeString(project.resolve("hello.txt"), "old");
        writeWorkflow(editWorkflow());
        var output = new ByteArrayOutputStream();
        var calls = new AtomicInteger();
        Fixture fixture = fixture(output, new AtomicReference<>(), calls);

        assertEquals(3, fixture.cli().execute(new String[] {"workflow", "workflow.json", "--run-id", "cancelled"}));
        String requestId = fixture.workflows().inspect("cancelled").approval().requestId();
        assertEquals(0, fixture.cli().execute(new String[] {"cancel", "cancelled"}));
        assertEquals(1, fixture.cli().execute(new String[] {"approve", "cancelled", requestId}));
        output.reset();
        assertEquals(1, fixture.cli().execute(new String[] {"resume", "cancelled"}));
        assertEquals("old", Files.readString(project.resolve("hello.txt")));
        assertEquals(1, calls.get());
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("result=CANCELLED"));
        assertFalse(output.toString(StandardCharsets.UTF_8).contains("승인: omluke approve"));
    }

    @Test void existingPresetCommandsStillWorkWithBothProvidersConfigured() throws Exception {
        Files.writeString(project.resolve("hello.txt"), "old");
        Files.writeString(project.resolve("task.json"), PresetJson.encode(task()));
        var output = new ByteArrayOutputStream();
        var calls = new AtomicInteger();
        Fixture fixture = fixture(output, new AtomicReference<>(), calls);

        assertEquals(0, fixture.cli().execute(new String[] {"run", "task.json", "--run-id", "preset"}));
        assertEquals(0, fixture.cli().execute(new String[] {"inspect", "preset"}));
        assertEquals(0, fixture.cli().execute(new String[] {"resume", "preset"}));
        var permissionsBefore = new ProjectPermissionStore(project).load();
        assertEquals(1, fixture.cli().execute(new String[] {"approve", "preset", "request"}));
        assertEquals(permissionsBefore, new ProjectPermissionStore(project).load());
        assertEquals(1, calls.get());
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("result=SUCCEEDED"));
    }

    private OmlukeCli legacyCli(ByteArrayOutputStream output) {
        Clock clock = Clock.systemUTC();
        var permissions = new ProjectPermissionManager(new ProjectPermissionStore(project), clock);
        var runs = new ManagedRunService(new GraphRunner(new GraphValidator()),
                new CheckpointStore(project, new CheckpointCodec()),
                new EventLogStore(project, new RunEventCodec()), new HandoffStore(project),
                new RunLockManager(project));
        return new OmlukeCli(runs, GraphResolver.none(), permissions,
                new PrintStream(output, true, StandardCharsets.UTF_8),
                new PrintStream(output, true, StandardCharsets.UTF_8));
    }

    private Fixture fixture(ByteArrayOutputStream output, AtomicReference<TaskSpec> selected, AtomicInteger calls) {
        Clock clock = Clock.systemUTC();
        var permissions = new ProjectPermissionManager(new ProjectPermissionStore(project), clock);
        var runs = new ManagedRunService(new GraphRunner(new GraphValidator()),
                new CheckpointStore(project, new CheckpointCodec()),
                new EventLogStore(project, new RunEventCodec()), new HandoffStore(project),
                new RunLockManager(project));
        Function<TaskSpec, AiRuntime> runtimeFactory = task -> {
            selected.set(task);
            return new AiRuntime() {
                @Override public String fingerprint() { return "workflow-cli-fixture:" + task.model() + ":" + task.reasoning(); }
                @Override public AiRuntimeResult invoke(AiRequest request) {
                    calls.incrementAndGet();
                    return AiRuntimeResult.success("{\"path\":\"hello.txt\",\"content\":\"ready\"}",
                            AiTokenUsage.measured(7, 0, 3, 0, "fixture"));
                }
            };
        };
        var sandbox = new UnavailableProcessSandbox("fixture");
        var workflows = new WorkflowRunService(project, runtimeFactory, permissions, sandbox, clock);
        var presets = new PresetRunService(project, runtimeFactory, permissions, sandbox, clock);
        return new Fixture(new OmlukeCli(runs, GraphResolver.none(), permissions,
                new PrintStream(output, true, StandardCharsets.UTF_8),
                new PrintStream(output, true, StandardCharsets.UTF_8), presets, workflows), workflows);
    }

    private void writeWorkflow(WorkflowSpec spec) throws Exception {
        Files.writeString(project.resolve("workflow.json"), PresetJson.encode(spec));
    }

    private static WorkflowSpec editWorkflow() {
        return new WorkflowSpec(1, "Make ready after reviewing proposal", "check", List.of(
                WorkflowStep.check("check", "hello.txt", validation(), "succeeded", "edit"),
                WorkflowStep.edit("edit", task(), true, "succeeded", "stopped")), 30, 0, 60_000);
    }

    private static TaskSpec task() {
        return new TaskSpec(1, "Make ready", "hello.txt", ExecutionMode.DIRECT, 1, 0, 60_000, 2,
                validation(), "file-model", "medium");
    }

    private static ValidationSpec validation() { return new ValidationSpec(List.of("ready"), List.of(), null); }

    private record Fixture(OmlukeCli cli, WorkflowRunService workflows) {}
}
