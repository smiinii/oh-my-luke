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
import io.ohmyluke.preset.TaskSpec;
import io.ohmyluke.ai.AiRuntime;
import io.ohmyluke.ai.codex.CodexCliConfiguration;
import io.ohmyluke.ai.codex.CodexCliRuntime;
import io.ohmyluke.ai.codex.CodexReasoningEffort;
import io.ohmyluke.tool.PlatformProcessSandbox;
import io.ohmyluke.tool.ProcessSandbox;
import java.util.function.Function;

/** Entry point for the Oh My Luke command-line application. */
public final class OmlukeApplication {
    private static final String PRODUCT_NAME = "Oh My Luke";

    private OmlukeApplication() {
    }

    public static void main(String[] args) {
        ManagedRunService runs = new ManagedRunService(
                new GraphRunner(new GraphValidator()),
                new CheckpointStore(Path.of(""), new CheckpointCodec()),
                new EventLogStore(Path.of(""), new RunEventCodec()),
                new HandoffStore(Path.of("")),
                new RunLockManager(Path.of("")));
        ProjectPermissionManager permissions = new ProjectPermissionManager(
                new ProjectPermissionStore(Path.of("")),
                Clock.systemUTC());
        Function<TaskSpec, AiRuntime> runtimeFactory = task -> {
            CodexCliConfiguration configuration = CodexCliConfiguration.defaults(Path.of(""))
                    .withTimeout(Duration.ofMillis(Math.min(300_000, task.maxElapsedMillis())));
            if (task.model() != null) { configuration = configuration.withModel(task.model()); }
            if (task.reasoning() != null) {
                configuration = configuration.withReasoning(CodexReasoningEffort.valueOf(
                        task.reasoning().toUpperCase(java.util.Locale.ROOT)));
            }
            return new CodexCliRuntime(configuration);
        };
        ProcessSandbox sandbox = PlatformProcessSandbox.detect();
        PresetRunService presets = new PresetRunService(Path.of(""), runtimeFactory,
                permissions, sandbox, Clock.systemUTC());
        WorkflowRunService workflows = new WorkflowRunService(Path.of(""), runtimeFactory,
                permissions, sandbox, Clock.systemUTC());
        int exitCode = new OmlukeCli(runs, GraphResolver.none(), permissions, System.out, System.err,
                presets, workflows)
                .execute(args);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    static String productName() {
        return PRODUCT_NAME;
    }
}
