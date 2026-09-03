package io.ohmyluke.cli;

import static org.junit.jupiter.api.Assertions.*;

import io.ohmyluke.preset.PresetJson;
import io.ohmyluke.preset.RunSelection;
import io.ohmyluke.preset.StartSpec;
import io.ohmyluke.preset.ValidationSpec;
import io.ohmyluke.preset.WorkflowSpec;
import io.ohmyluke.preset.WorkflowStep;
import io.ohmyluke.state.CheckpointCodec;
import io.ohmyluke.state.RunCheckpoint;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Exercises the real entry point with an AI-free approval/check workflow and separate JVMs. */
class StartProcessTest {
    @TempDir Path directory;

    @Test void nonInteractiveEntryPointFailsFastWithoutAnExplicitChoice() throws Exception {
        Path project = Files.createDirectory(directory.resolve("project"));
        Result result = cli(project, "start", "job.json", "--run-id", "not-started");
        result.expect(2, "--mode");
        assertFalse(Files.exists(project.resolve(".oml/runs/not-started/state.json")));
    }

    @Test void newProcessesRestoreTheChosenModeAndReasonWithoutPromptingOrRerouting() throws Exception {
        Path project = Files.createDirectory(directory.resolve("project"));
        Files.writeString(project.resolve("hello.txt"), "ready");
        var workflow = new WorkflowSpec(1, "Check after explicit approval", "gate", List.of(
                WorkflowStep.approval("gate", "Continue with the fixed check?", "check"),
                WorkflowStep.check("check", "hello.txt", new ValidationSpec(List.of("ready"), List.of(), null),
                        "succeeded", "stopped")), 20, 0, 60_000);
        Files.writeString(project.resolve("job.json"), PresetJson.encode(new StartSpec(1, null, workflow)));
        cli(project, "start", "job.json", "--mode", "auto", "--run-id", "saved")
                .expect(3, "selectionStrategy=AUTO", "mode=WORKFLOW", "result=WAITING_APPROVAL");
        RunCheckpoint waiting = checkpoint(project);
        String selection = waiting.state().values().get(RunSelection.STATE_KEY);
        assertNotNull(selection);
        String request = waiting.approval().requestId();
        Files.writeString(project.resolve("job.json"), "invalid after start");
        cli(project, "inspect", "saved").expect(0, "selectionStrategy=AUTO", "mode=WORKFLOW",
                "selectionReason=auto-workflow-declared", "selectionRuleVersion=1");
        cli(project, "resume", "saved").expect(3, "result=WAITING_APPROVAL", "aiAttempts=0");
        cli(project, "approve", "saved", request).expect(0, "approvalDecision=APPROVED");
        cli(project, "resume", "saved").expect(0, "result=SUCCEEDED", "selectionStrategy=AUTO", "recordedUsage=0");
        cli(project, "resume", "saved").expect(0, "result=SUCCEEDED", "aiAttempts=0");
        assertEquals(selection, checkpoint(project).state().values().get(RunSelection.STATE_KEY));
        assertFalse(Files.exists(project.resolve(".oml/runtime/codex/invocations")));
        assertEquals("ready", Files.readString(project.resolve("hello.txt")));
    }

    private Result cli(Path project, String... args) throws Exception {
        String classpath = Arrays.stream(System.getProperty("java.class.path").split(Pattern.quote(File.pathSeparator)))
                .map(entry -> Path.of(entry).toAbsolutePath().toString())
                .collect(java.util.stream.Collectors.joining(File.pathSeparator));
        List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", classpath, OmlukeApplication.class.getName()));
        command.addAll(List.of(args));
        Path output = Files.createTempFile(directory, "start-cli-", ".txt");
        ProcessBuilder builder = new ProcessBuilder(command).directory(project.toFile())
                .redirectErrorStream(true).redirectOutput(output.toFile());
        // This fixture contains no AI node; also prevent accidental lookup of the user's Codex CLI.
        builder.environment().put("PATH", directory.resolve("no-executables").toString());
        Process process = builder.start();
        process.getOutputStream().close();
        try {
            assertTrue(process.waitFor(20, TimeUnit.SECONDS), "noninteractive CLI must not wait for input");
            assertTrue(Files.size(output) < 64 * 1024);
            return new Result(process.exitValue(), Files.readString(output));
        } finally {
            if (process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
        }
    }

    private static RunCheckpoint checkpoint(Path project) throws Exception {
        return new CheckpointCodec().decode(Files.readString(project.resolve(".oml/runs/saved/state.json")));
    }

    private record Result(int exitCode, String output) {
        void expect(int code, String... snippets) {
            assertEquals(code, exitCode, output);
            for (String snippet : snippets) { assertTrue(output.contains(snippet), output); }
        }
    }
}
