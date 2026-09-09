package io.ohmyluke.cli;

import static org.junit.jupiter.api.Assertions.*;

import io.ohmyluke.preset.*;
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

/** No AI, no network, no access to the real user's profiles: exercises the distributed CLI entry point. */
class ProfileProcessTest {
    @TempDir Path directory;

    @Test void setupAndSwitchPreserveChoicesWithoutChangingOtherToolsOrCreatingRunRecords() throws Exception {
        Path home = home();
        Path codex = Files.createDirectory(home.resolve(".codex")).resolve("config.toml");
        Files.writeString(codex, "model = 'untouched'");
        cli(home, "setup").expect(0, "OML 기본 설정", "미선택");
        cli(home, "switch", "--scope", "global", "--model", "model-a").expect(0, "model-a");
        String original = Files.readString(home.resolve(".oml/settings.json"));
        cli(home, "setup").expect(0, "기존 설정", "model-a");
        assertEquals(original, Files.readString(home.resolve(".oml/settings.json")));
        Path repo = repo("repo");
        cli(repo, "status").expect(0, "model-a", "사용자 기본 설정", repo.resolve(".oml/runs").toString());
        assertFalse(Files.exists(repo.resolve(".oml")));
        assertFalse(Files.exists(home.resolve(".oml/runs")));
        assertEquals("model = 'untouched'", Files.readString(codex));
        cli(repo, "switch", "--scope", "project", "--inherit-model").expect(0, "프로젝트 고정", "실행기 설정 그대로 사용");
        cli(repo, "switch", "--scope", "project", "--inherit").expect(0, "model-a", "사용자 기본 설정");
        cli(repo, "switch", "--scope", "global", "--model", "bad\nmodel").expect(1, "모델 ID 형식");
        assertEquals(original, Files.readString(home.resolve(".oml/settings.json")));
    }

    @Test void savedSelectionSurvivesChangedAndCorruptSettingsWhileSubdirectoryResumeUsesOneRoot() throws Exception {
        Path project = repo("first");
        Path sub = Files.createDirectories(project.resolve("src/deep"));
        writeWorkflow(project, null);
        cli(project, "switch", "--scope", "global", "--model", "global-model").expect(0);
        cli(project, "switch", "--scope", "project", "--model", "project-model").expect(0);
        cli(sub, "start", "../../job.json", "--mode", "auto", "--run-id", "saved").expect(3, "WAITING_APPROVAL");
        assertEquals("project-model", savedModel(project, "saved"));
        cli(project, "switch", "--scope", "project", "--model", "changed-model").expect(0);
        // A damaged *current* profile must not change or prevent resuming the already saved contract.
        Files.writeString(home().resolve(".oml/settings.json"), "broken");
        cli(sub, "status").expect(1, "자동으로 덮어쓰지 않습니다");
        cli(sub, "inspect", "saved").expect(0, "WAITING_APPROVAL");
        cli(sub, "approve", "saved", checkpoint(project, "saved").approval().requestId()).expect(0, "APPROVED");
        cli(sub, "resume", "saved").expect(0, "SUCCEEDED", "aiAttempts=0");
        assertEquals("project-model", savedModel(project, "saved"));
        assertFalse(Files.exists(sub.resolve(".oml")));
        Path other = repo("second");
        cli(other, "inspect", "saved").expect(1);
        assertFalse(Files.exists(other.resolve(".oml/runs/saved/state.json")));
    }

    @Test void taskAndRunOverridesAreSavedWithoutOverwritingDefaults() throws Exception {
        Path project = repo("repo");
        cli(project, "switch", "--scope", "global", "--model", "global-model").expect(0);
        writeWorkflow(project, "task-model");
        cli(project, "start", "job.json", "--mode", "auto", "--run-id", "contract").expect(3);
        assertEquals("task-model", savedModel(project, "contract"));
        cli(project, "start", "job.json", "--mode", "auto", "--run-id", "override", "--model", "run-model").expect(3);
        assertEquals("run-model", savedModel(project, "override"));
        cli(project, "status").expect(0, "global-model");
    }

    @Test void explicitProjectWorksFromHomeAndAmbiguousImplicitRunsFailBeforeCreatingRecords() throws Exception {
        Path home = home();
        Path project = repo("repo");
        writeWorkflow(project, null);
        cli(home, "start", "job.json", "--mode", "auto").expect(1, "작업 폴더를 지정");
        assertFalse(Files.exists(home.resolve(".oml")));
        cli(home, "--project", project.toString(), "start", "job.json", "--mode", "auto", "--run-id", "explicit").expect(3);
        cli(home, "--project", project.toString(), "inspect", "explicit").expect(0, "WAITING_APPROVAL");
        assertFalse(Files.exists(home.resolve(".oml")));
    }

    @Test void isolatedEnvironmentHomeDoesNotReadOrOverwriteTheOperatingSystemHomeSettings() throws Exception {
        Path osHome = home();
        Files.createDirectory(osHome.resolve(".oml"));
        Files.writeString(osHome.resolve(".oml/settings.json"), "intentionally unreadable as a profile");
        Path isolated = Files.createDirectory(directory.resolve("isolated-home"));
        cliWithHome(osHome, isolated, "setup").expect(0, "OML 기본 설정");
        assertTrue(Files.exists(isolated.resolve(".oml/settings.json")));
        assertEquals("intentionally unreadable as a profile", Files.readString(osHome.resolve(".oml/settings.json")));
        cliWithHome(isolated, isolated, "status").expect(0, "미선택");
        cliWithHome(isolated, isolated, "start", "job.json", "--mode", "auto").expect(1, "작업 폴더를 지정");
        assertFalse(Files.exists(isolated.resolve(".oml/runs")));
        cliWithHome(osHome, directory.resolve("missing-home"), "--version").expect(0, "omluke");
    }

    @Test void anotherProcessHoldingTheSettingsLockFailsFastWithoutOverwriting() throws Exception {
        Path home = home();
        cli(home, "setup").expect(0);
        String original = Files.readString(home.resolve(".oml/settings.json"));
        try (var channel = java.nio.channels.FileChannel.open(home.resolve(".oml/settings.json.lock"),
                java.nio.file.StandardOpenOption.WRITE); var ignored = channel.lock()) {
            cli(home, "switch", "--scope", "global", "--model", "new-model").expect(1, "다른 OML 프로세스");
        }
        assertEquals(original, Files.readString(home.resolve(".oml/settings.json")));
    }

    @Test void corruptProjectPreventsPartialGlobalUpdatesAndInvalidArgumentsDoNotCreateSettings() throws Exception {
        Path project = repo("repo");
        cli(project, "switch", "--scope", "guess", "--model", "model-a").expect(2, "사용법");
        assertFalse(Files.exists(home().resolve(".oml")));
        assertFalse(Files.exists(project.resolve(".oml")));
        Files.createDirectory(project.resolve(".oml"));
        Files.writeString(project.resolve(".oml/profile.json"), "broken");
        cli(project, "setup").expect(1, "자동으로 덮어쓰지 않습니다");
        cli(project, "switch", "--scope", "global", "--model", "model-a").expect(1);
        assertFalse(Files.exists(home().resolve(".oml")));
    }

    private void writeWorkflow(Path project, String model) throws Exception {
        Files.writeString(project.resolve("hello.txt"), "ready");
        TaskSpec task = new TaskSpec(1, "Check the ready file", "hello.txt", ExecutionMode.DIRECT,
                1, 0, 60_000, 1, new ValidationSpec(List.of("ready"), List.of(), null), model, null);
        var workflow = new WorkflowSpec(1, "Approval then deterministic check", "gate", List.of(
                WorkflowStep.approval("gate", "Continue?", "check"),
                WorkflowStep.check("check", "hello.txt", task.validation(), "succeeded", "edit"),
                WorkflowStep.edit("edit", task, false, "succeeded", "stopped")), 20, 0, 60_000);
        Files.writeString(project.resolve("job.json"), PresetJson.encode(new StartSpec(1, null, workflow)));
    }

    private Path home() throws Exception { return Files.createDirectories(directory.resolve("user-home")).toRealPath(); }
    private Path repo(String name) throws Exception { return Files.createDirectories(directory.resolve(name + "/.git")).getParent().toRealPath(); }
    private RunCheckpoint checkpoint(Path project, String id) throws Exception {
        return new CheckpointCodec().decode(Files.readString(project.resolve(".oml/runs/" + id + "/state.json")));
    }
    private String savedModel(Path project, String id) throws Exception {
        return PresetJson.decode(checkpoint(project, id).state().values().get("workflow.spec"), WorkflowSpec.class)
                .steps().stream().filter(step -> step.task() != null).findFirst().orElseThrow().task().model();
    }

    private Result cli(Path cwd, String... args) throws Exception {
        return cliWithHome(cwd, home(), args);
    }

    private Result cliWithHome(Path cwd, Path settingsHome, String... args) throws Exception {
        String classpath = Arrays.stream(System.getProperty("java.class.path").split(Pattern.quote(File.pathSeparator)))
                .map(entry -> Path.of(entry).toAbsolutePath().toString()).collect(java.util.stream.Collectors.joining(File.pathSeparator));
        List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Duser.home=" + home(), "-cp", classpath, OmlukeApplication.class.getName()));
        command.addAll(List.of(args));
        Path output = Files.createTempFile(directory, "profile-cli-", ".txt");
        var builder = new ProcessBuilder(command).directory(cwd.toFile()).redirectErrorStream(true).redirectOutput(output.toFile());
        builder.environment().put("PATH", directory.resolve("no-executables").toString());
        builder.environment().put("HOME", settingsHome.toString());
        builder.environment().remove("OPENAI_API_KEY");
        builder.environment().remove("CODEX_API_KEY");
        Process process = builder.start();
        process.getOutputStream().close();
        try {
            assertTrue(process.waitFor(20, TimeUnit.SECONDS), "CLI must not hang or prompt in a non-TTY");
            assertTrue(Files.size(output) < 64 * 1024);
            return new Result(process.exitValue(), Files.readString(output));
        } finally {
            if (process.isAlive()) { process.descendants().forEach(ProcessHandle::destroyForcibly); process.destroyForcibly(); }
        }
    }
    private record Result(int code, String output) {
        void expect(int expected, String... snippets) {
            assertEquals(expected, code, output);
            for (String snippet : snippets) { assertTrue(output.contains(snippet), output); }
        }
    }
}
