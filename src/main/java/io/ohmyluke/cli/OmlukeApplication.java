package io.ohmyluke.cli;

import io.ohmyluke.graph.GraphRunner;
import io.ohmyluke.graph.GraphValidator;
import io.ohmyluke.runtime.ManagedRunService;
import io.ohmyluke.state.CheckpointCodec;
import io.ohmyluke.state.CheckpointStore;
import io.ohmyluke.state.EventLogStore;
import io.ohmyluke.state.HandoffStore;
import io.ohmyluke.state.RunEventCodec;
import io.ohmyluke.state.RunLockManager;
import io.ohmyluke.state.ProjectPermissionManager;
import io.ohmyluke.state.ProjectPermissionStore;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import io.ohmyluke.preset.PresetRunService;
import io.ohmyluke.preset.WorkflowRunService;
import io.ohmyluke.preset.StartRunService;
import io.ohmyluke.preset.TaskSpec;
import io.ohmyluke.ai.AiRuntime;
import io.ohmyluke.ai.codex.CodexCliConfiguration;
import io.ohmyluke.ai.codex.CodexCliRuntime;
import io.ohmyluke.ai.codex.CodexReasoningEffort;
import io.ohmyluke.tool.PlatformProcessSandbox;
import io.ohmyluke.tool.ProcessSandbox;
import java.io.IOException;
import java.io.InputStream;
import java.util.function.Function;
import java.util.Properties;

/** Entry point for the Oh My Luke command-line application. */
public final class OmlukeApplication {
    private static final String PRODUCT_NAME = "Oh My Luke";
    private static final String PRODUCT_VERSION = loadProductVersion();

    private OmlukeApplication() {
    }

    public static void main(String[] args) {
        int exitCode;
        try { exitCode = execute(args); }
        catch (RuntimeException | IOException failure) {
            System.err.println("오류: " + ProfileCli.printable(failure.getMessage() == null ? "실행을 시작할 수 없습니다." : failure.getMessage()));
            exitCode = 1;
        }
        if (exitCode != 0) { System.exit(exitCode); }
    }

    private static int execute(String[] originalArgs) throws IOException {
        Path cwd = Path.of("").toRealPath();
        Path home = Path.of(System.getProperty("user.home"));
        // Respect isolated HOME environments without mistaking a redirected config home for the OS home boundary.
        String environmentHome = System.getenv("HOME");
        Path settingsHome = environmentHome == null || environmentHome.isBlank() ? home : Path.of(environmentHome);
        Path explicitProject = null;
        String[] args = originalArgs.clone();
        if (args.length > 0 && args[0].equals("--project")) {
            if (args.length < 3) { throw new IllegalArgumentException("사용법: omluke --project <작업폴더> <명령>"); }
            explicitProject = Path.of(args[1]);
            args = java.util.Arrays.copyOfRange(args, 2, args.length);
        }
        String command = args.length == 0 ? "--help" : args[0];
        boolean informational = java.util.Set.of("--help", "-h", "--version", "-V").contains(command);
        if (!informational && !settingsHome.isAbsolute()) { throw new IllegalArgumentException("HOME은 기존 절대 폴더 경로여야 합니다."); }
        boolean profileCommand = java.util.Set.of("setup", "status", "switch").contains(command);
        Path project = informational ? cwd
                : explicitProject == null && profileCommand && io.ohmyluke.profile.ProjectLocator.needsWorkFolder(cwd, home, settingsHome)
                        ? null : io.ohmyluke.profile.ProjectLocator.locate(cwd, home, explicitProject, settingsHome);
        var profiles = informational ? null : new io.ohmyluke.profile.ExecutionProfiles(settingsHome, project);
        if (profileCommand) { return new ProfileCli(profiles, project, System.out).execute(args); }
        if (!informational) {
            System.out.println("projectRoot=" + ProfileCli.printable(project.toString()));
            if (java.util.Set.of("start", "run", "workflow").contains(command) && args.length >= 2) {
                // Auto detection retains caller-relative contract paths; --project behaves like changing directory.
                Path base = explicitProject == null ? cwd : project;
                Path contract = base.resolve(args[1]).toAbsolutePath().normalize();
                if (!contract.startsWith(project)) { throw new IllegalArgumentException("작업표는 선택한 프로젝트 안에 있어야 합니다."); }
                args[1] = project.relativize(contract).toString();
            }
        }
        ManagedRunService runs = new ManagedRunService(
                new GraphRunner(new GraphValidator()),
                new CheckpointStore(project, new CheckpointCodec()),
                new EventLogStore(project, new RunEventCodec()),
                new HandoffStore(project),
                new RunLockManager(project));
        ProjectPermissionManager permissions = new ProjectPermissionManager(
                new ProjectPermissionStore(project),
                Clock.systemUTC());
        Function<TaskSpec, AiRuntime> runtimeFactory = task -> {
            CodexCliConfiguration configuration = CodexCliConfiguration.defaults(project)
                    .withTimeout(Duration.ofMillis(Math.min(300_000, task.maxElapsedMillis())));
            if (task.model() != null) { configuration = configuration.withModel(task.model()); }
            if (task.reasoning() != null) {
                configuration = configuration.withReasoning(CodexReasoningEffort.valueOf(
                        task.reasoning().toUpperCase(java.util.Locale.ROOT)));
            }
            return new CodexCliRuntime(configuration);
        };
        ProcessSandbox sandbox = PlatformProcessSandbox.detect();
        PresetRunService presets = new PresetRunService(project, runtimeFactory,
                permissions, sandbox, Clock.systemUTC());
        WorkflowRunService workflows = new WorkflowRunService(project, runtimeFactory,
                permissions, sandbox, Clock.systemUTC());
        StartRunService starts = new StartRunService(project, presets, workflows, permissions, Clock.systemUTC());
        return new OmlukeCli(runs, GraphResolver.none(), permissions, System.out, System.err,
                presets, workflows, starts, StartPrompt.system(), () -> profiles == null
                        ? io.ohmyluke.profile.ExecutionProfile.defaults() : profiles.resolve().profile())
                .execute(args);
    }

    static String productName() {
        return PRODUCT_NAME;
    }

    static String productVersion() {
        return PRODUCT_VERSION;
    }

    private static String loadProductVersion() {
        Properties properties = new Properties();
        try (InputStream input = OmlukeApplication.class.getResourceAsStream("/io/ohmyluke/version.properties")) {
            if (input == null) {
                throw new IllegalStateException("missing product version resource");
            }
            properties.load(input);
        } catch (IOException error) {
            throw new IllegalStateException("cannot read product version resource", error);
        }
        String version = properties.getProperty("version", "").strip();
        if (!version.matches("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[0-9A-Za-z]+(?:[.-][0-9A-Za-z]+)*)?")) {
            throw new IllegalStateException("invalid product version resource");
        }
        return version;
    }
}
