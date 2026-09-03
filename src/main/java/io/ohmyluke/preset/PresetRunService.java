package io.ohmyluke.preset;

import io.ohmyluke.ai.AiRuntime;
import io.ohmyluke.graph.*;
import io.ohmyluke.policy.*;
import io.ohmyluke.runtime.ManagedRunService;
import io.ohmyluke.runtime.RunInspection;
import io.ohmyluke.state.*;
import io.ohmyluke.tool.*;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/** Persists the fixed contract with the run and rebuilds the same preset after a process restart. */
public final class PresetRunService {
    private final Path project;
    private final Function<TaskSpec, AiRuntime> runtimes;
    private final ToolPermissionEvaluator permissions;
    private final ProcessSandbox sandbox;
    private final Clock clock;

    public PresetRunService(Path project, Function<TaskSpec, AiRuntime> runtimes,
                            ToolPermissionEvaluator permissions, ProcessSandbox sandbox, Clock clock) {
        this.project = Objects.requireNonNull(project, "project");
        this.runtimes = Objects.requireNonNull(runtimes, "runtimes");
        this.permissions = Objects.requireNonNull(permissions, "permissions");
        this.sandbox = Objects.requireNonNull(sandbox, "sandbox");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public void start(String runId, TaskSpec task) {
        startInternal(runId, task, null);
    }

    public void start(String runId, TaskSpec task, RunSelection selection) {
        new StartPlan(selection, task, null);
        startInternal(runId, task, selection);
    }

    private void startInternal(String runId, TaskSpec task, RunSelection selection) {
        Objects.requireNonNull(task, "task");
        if (task.validation().command() != null && Path.of(task.validation().command().executable())
                .toAbsolutePath().normalize().equals(project.resolve(task.file()).toAbsolutePath().normalize())) {
            throw new IllegalArgumentException("the editable file cannot be the validator executable");
        }
        Map<String, String> initial = selection == null
                ? Map.of(PresetGraph.TASK, PresetJson.encode(task), "preset.status", PresetStatus.RUNNING.name())
                : Map.of(PresetGraph.TASK, PresetJson.encode(task), "preset.status", PresetStatus.RUNNING.name(),
                        RunSelection.STATE_KEY, PresetJson.encode(selection));
        runs(task).start(runId, graph(runId, task), initial,
                new HandoffNote(task.goal(), List.of("mode=" + task.mode(), "contract is fixed for this run"),
                        List.of(), List.of(), List.of("Do not change the validation or permission policy"),
                        "omluke resume " + runId));
    }

    public boolean supports(String runId) { return inspection(runId).state().values().containsKey(PresetGraph.TASK); }

    public TaskSpec readTask(Path path) {
        TaskSpec.relativeFile(path.toString());
        FileToolResult input = new FileTool(project, "preset-input", permissions, clock)
                .execute(FileToolRequest.read("task-input", path));
        if (!input.executed() || input.content().length > 512 * 1024) {
            throw new IllegalArgumentException("cannot read bounded task file");
        }
        TaskSpec task = PresetJson.decode(new String(input.content(), java.nio.charset.StandardCharsets.UTF_8), TaskSpec.class);
        if (task.file().equals(path.toString())) {
            throw new IllegalArgumentException("task contract cannot be the editable target");
        }
        return task;
    }

    public PresetResult resume(String runId) {
        TaskSpec task = task(runId);
        ManagedRunService runs = runs(task);
        GraphDefinition graph = graph(runId, task);
        // Keep the kernel's one-run execution lock and durable per-node boundary.
        runs.resume(runId, graph);
        PresetResult result = inspect(runId);
        if (result.status() == PresetStatus.SUCCEEDED) {
            runs.evaluateCompletion(runId, new CompletionCondition.RequirementSatisfied("preset.validation"),
                    new CompletionFacts(Map.of(), Set.of(), 0, Set.of("preset.validation")));
            result = inspect(runId);
        }
        return result;
    }

    public PresetResult step(String runId) {
        TaskSpec task = task(runId);
        runs(task).step(runId, graph(runId, task));
        return inspect(runId);
    }

    public PresetResult inspect(String runId) {
        RunInspection inspection = inspection(runId);
        Map<String, String> values = inspection.state().values();
        PresetStatus status = PresetStatus.valueOf(values.getOrDefault("preset.status", "RUNNING"));
        String reason = values.getOrDefault("preset.reason", "pending");
        if (inspection.state().status() == RunStatus.CANCELLED) { status = PresetStatus.CANCELLED; reason = "cancelled"; }
        else if (inspection.policyState().lastDecision().outcome() != PolicyOutcome.CONTINUE
                && inspection.policyState().lastDecision().outcome() != PolicyOutcome.SUCCESS) {
            status = PresetStatus.LIMIT_REACHED;
            reason = inspection.policyState().lastDecision().reasonCode();
        } else if (inspection.state().status() == RunStatus.STEP_LIMIT_REACHED) {
            status = PresetStatus.LIMIT_REACHED; reason = "graph-step-limit";
        } else if (inspection.state().status() == RunStatus.RUNNING) {
            status = PresetStatus.RUNNING;
        }
        return new PresetResult(runId, status, reason, Integer.parseInt(values.getOrDefault("preset.attempts", "0")),
                inspection.policyState().usage(), Boolean.parseBoolean(values.getOrDefault("preset.allTokensAvailable", "false")));
    }

    private RunInspection inspection(String runId) { return runs(null).inspect(runId); }
    private TaskSpec task(String runId) {
        return PresetJson.decode(inspection(runId).state().values().get(PresetGraph.TASK), TaskSpec.class);
    }

    private GraphDefinition graph(String runId, TaskSpec task) {
        AiRuntime runtime = Objects.requireNonNull(runtimes.apply(task), "runtime");
        return new PresetGraph(task, runtime, new FileTool(project, runId, permissions, clock),
                new ProcessTool(project, runId, permissions, sandbox), project, runId).definition();
    }

    private ManagedRunService runs(TaskSpec task) {
        PolicyConfiguration policy = task == null ? PolicyConfiguration.unlimited()
                : new PolicyConfiguration(0, task.maxElapsedMillis(), 0, 0, task.maxUsage(), 0, 0);
        return new ManagedRunService(new GraphRunner(new GraphValidator()), new CheckpointStore(project, new CheckpointCodec()),
                new EventLogStore(project, new RunEventCodec()), new HandoffStore(project), new RunLockManager(project), policy, clock);
    }

}
