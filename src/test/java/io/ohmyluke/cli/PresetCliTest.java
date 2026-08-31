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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PresetCliTest {
    @TempDir Path project;

    @Test void runModelOverrideInspectAndRestartResumeUseOneStoredContract() throws Exception {
        Files.writeString(project.resolve("hello.txt"), "old");
        Files.writeString(project.resolve("task.json"), PresetJson.encode(task()));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        AtomicReference<TaskSpec> selected = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();
        OmlukeCli cli = cli(output, selected, calls);
        assertEquals(0, cli.execute(new String[] {"run", "task.json", "--run-id", "cli-run", "--model", "chosen-model", "--reasoning", "low"}));
        assertEquals("chosen-model", selected.get().model());
        assertEquals("low", selected.get().reasoning());
        Files.writeString(project.resolve("task.json"), "changed after start");
        OmlukeCli restarted = cli(output, selected, calls);
        assertEquals(0, restarted.execute(new String[] {"resume", "cli-run"}));
        assertEquals(0, restarted.execute(new String[] {"inspect", "cli-run"}));
        assertEquals(1, calls.get());
        assertTrue(output.toString().contains("result=SUCCEEDED"));
        assertTrue(output.toString().contains("allTokenUsageAvailable=true"));
        assertTrue(output.toString().contains("policyOutcome=SUCCESS"));
    }

    @Test void malformedOptionsDoNotInvokeAi() throws Exception {
        Files.writeString(project.resolve("task.json"), PresetJson.encode(task()));
        AtomicInteger calls = new AtomicInteger();
        OmlukeCli cli = cli(new ByteArrayOutputStream(), new AtomicReference<>(), calls);
        assertEquals(2, cli.execute(new String[] {"run", "task.json", "--model"}));
        assertEquals(2, cli.execute(new String[] {"run", "task.json", "--model", "one", "--model", "two"}));
        assertEquals(2, cli.execute(new String[] {"run", "task.json", "--unsafe", "true"}));
        assertEquals(0, calls.get());
    }

    @Test void failedValidationReturnsNonzeroEvenThoughGraphReachedTerminal() throws Exception {
        Files.writeString(project.resolve("hello.txt"), "old");
        TaskSpec failing = new TaskSpec(1, "Make ready", "hello.txt", ExecutionMode.DIRECT, 1, 0, 60_000, 2,
                new ValidationSpec(List.of("different-required-text"), List.of(), null), null, null);
        Files.writeString(project.resolve("task.json"), PresetJson.encode(failing));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        AtomicInteger calls = new AtomicInteger();
        assertEquals(1, cli(output, new AtomicReference<>(), calls).execute(new String[] {"run", "task.json"}));
        assertTrue(output.toString().contains("result=VALIDATION_FAILED"));
        assertEquals(1, calls.get());
    }

    private OmlukeCli cli(ByteArrayOutputStream output, AtomicReference<TaskSpec> selected, AtomicInteger calls) {
        Clock clock = Clock.systemUTC();
        var permissions = new ProjectPermissionManager(new ProjectPermissionStore(project), clock);
        var runs = new ManagedRunService(new GraphRunner(new GraphValidator()), new CheckpointStore(project, new CheckpointCodec()),
                new EventLogStore(project, new RunEventCodec()), new HandoffStore(project), new RunLockManager(project));
        var presets = new PresetRunService(project, task -> {
            selected.set(task);
            return new AiRuntime() {
                @Override public String fingerprint() { return "cli-fixture:" + task.model() + ":" + task.reasoning(); }
                @Override public AiRuntimeResult invoke(AiRequest request) {
                    calls.incrementAndGet();
                    return AiRuntimeResult.success("{\"path\":\"hello.txt\",\"content\":\"ready\"}",
                            AiTokenUsage.measured(7, 0, 3, 0, "fixture"));
                }
            };
        }, permissions, new UnavailableProcessSandbox("fixture"), clock);
        return new OmlukeCli(runs, GraphResolver.none(), permissions, new PrintStream(output), new PrintStream(output), presets);
    }

    private TaskSpec task() {
        return new TaskSpec(1, "Make ready", "hello.txt", ExecutionMode.DIRECT, 1, 0, 60_000, 2,
                new ValidationSpec(List.of("ready"), List.of(), null), null, null);
    }
}
