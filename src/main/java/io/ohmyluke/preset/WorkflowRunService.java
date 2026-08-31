package io.ohmyluke.preset;

import io.ohmyluke.ai.AiRuntime;
import io.ohmyluke.graph.*;
import io.ohmyluke.policy.*;
import io.ohmyluke.runtime.*;
import io.ohmyluke.state.*;
import io.ohmyluke.tool.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/** One durable execution, fixed declaration, and shared operational budget. */
public final class WorkflowRunService {
    private static final String SPEC = "workflow.spec";
    private final Path project;
    private final Function<TaskSpec, AiRuntime> runtimes;
    private final ToolPermissionEvaluator permissions;
    private final ProcessSandbox sandbox;
    private final Clock clock;

    public WorkflowRunService(Path project, Function<TaskSpec, AiRuntime> runtimes,
                              ToolPermissionEvaluator permissions, ProcessSandbox sandbox, Clock clock) {
        this.project = Objects.requireNonNull(project);
        this.runtimes = Objects.requireNonNull(runtimes);
        this.permissions = Objects.requireNonNull(permissions);
        this.sandbox = Objects.requireNonNull(sandbox);
        this.clock = Objects.requireNonNull(clock);
    }

    public WorkflowSpec readSpec(Path path) {
        TaskSpec.relativeFile(path.toString());
        FileToolResult input = new FileTool(project, "workflow-input", permissions, clock)
                .execute(FileToolRequest.read("workflow-input", path));
        if (!input.executed() || input.content().length > 512 * 1024) { throw new IllegalArgumentException("cannot read bounded workflow file"); }
        WorkflowSpec spec = PresetJson.decode(new String(input.content(), StandardCharsets.UTF_8), WorkflowSpec.class);
        if (spec.steps().stream().anyMatch(step -> step.task() != null && step.task().file().equals(path.toString()))) {
            throw new IllegalArgumentException("workflow contract cannot be the editable target");
        }
        return spec;
    }

    public void start(String runId, WorkflowSpec spec) {
        Objects.requireNonNull(spec);
        String encoded = PresetJson.encode(spec);
        if (encoded.getBytes(StandardCharsets.UTF_8).length > 512 * 1024) {
            throw new IllegalArgumentException("workflow contract exceeds 512 KiB");
        }
        Set<Path> targets = spec.steps().stream().filter(step -> step.task() != null)
                .map(step -> project.resolve(step.task().file()).toAbsolutePath().normalize()).collect(java.util.stream.Collectors.toSet());
        for (WorkflowStep step : spec.steps()) {
            ValidationSpec validation = step.task() == null ? step.validation() : step.task().validation();
            if (validation != null && validation.command() != null
                    && targets.contains(Path.of(validation.command().executable()).toAbsolutePath().normalize())) {
                throw new IllegalArgumentException("an editable file cannot be a workflow validator executable");
            }
        }
        runs(spec).start(runId, graph(runId, spec), Map.of(SPEC, encoded, "workflow.status", "RUNNING"),
                new HandoffNote(spec.goal(), List.of("mode=WORKFLOW", "static declaration and budget fixed at start"),
                        List.of(), List.of(), List.of("Approval gates do not grant tool permissions"), "omluke inspect " + runId));
    }

    public boolean supports(String runId) { return inspection(runId).state().values().containsKey(SPEC); }
    public WorkflowResult resume(String runId) {
        WorkflowSpec spec = spec(runId);
        ManagedRunService runs = runs(spec);
        runs.resume(runId, graph(runId, spec));
        if (inspect(runId).status() == WorkflowStatus.SUCCEEDED) {
            runs.evaluateCompletion(runId, new CompletionCondition.RequirementSatisfied("workflow.validation"),
                    new CompletionFacts(Map.of(), Set.of(), 0, Set.of("workflow.validation")));
        }
        return inspect(runId);
    }
    public WorkflowResult step(String runId) {
        WorkflowSpec spec = spec(runId);
        runs(spec).step(runId, graph(runId, spec));
        return inspect(runId);
    }
    public WorkflowResult decideApproval(String runId, String requestId, boolean approved) {
        WorkflowSpec spec = spec(runId);
        runs(spec).decideApproval(runId, graph(runId, spec), requestId, approved);
        return inspect(runId);
    }

    public WorkflowResult inspect(String runId) {
        RunInspection inspection = inspection(runId);
        Map<String, String> values = inspection.state().values();
        WorkflowStatus status = WorkflowStatus.valueOf(values.getOrDefault("workflow.status", "RUNNING"));
        String reason = values.getOrDefault("workflow.reason", "pending");
        PolicyOutcome outcome = inspection.policyState().lastDecision().outcome();
        if (inspection.state().status() == RunStatus.CANCELLED) { status = WorkflowStatus.CANCELLED; reason = "cancelled"; }
        else if (outcome != PolicyOutcome.CONTINUE && outcome != PolicyOutcome.SUCCESS) {
            status = outcome == PolicyOutcome.BLOCKED ? WorkflowStatus.BLOCKED : WorkflowStatus.LIMIT_REACHED;
            reason = inspection.policyState().lastDecision().reasonCode();
        } else if (inspection.state().status() == RunStatus.STEP_LIMIT_REACHED) { status = WorkflowStatus.LIMIT_REACHED; reason = "graph-step-limit"; }
        else if (inspection.state().status() == RunStatus.COMPLETED) {
            if (inspection.state().currentNode().value().equals("succeeded")) { status = WorkflowStatus.SUCCEEDED; reason = "validation-passed"; }
            else if (!inspection.state().events().isEmpty() && inspection.state().events().getLast().outcome() == Outcome.FAILURE
                    && isApprovalNode(spec(runId), inspection.state().events().getLast().node().value())) {
                status = WorkflowStatus.BLOCKED; reason = "approval-denied";
            } else if (status == WorkflowStatus.RUNNING) { status = WorkflowStatus.BLOCKED; reason = "workflow-stopped"; }
        } else if (inspection.approval() != null && inspection.approval().decision() == ApprovalDecision.PENDING) {
            status = WorkflowStatus.WAITING_APPROVAL; reason = "human-approval-required";
        } else { status = WorkflowStatus.RUNNING; }
        int attempts = values.entrySet().stream().filter(entry -> entry.getKey().endsWith(".preset.attempts"))
                .mapToInt(entry -> Integer.parseInt(entry.getValue())).sum();
        boolean reported = attempts == 0 || values.entrySet().stream().filter(entry -> entry.getKey().endsWith(".preset.attempts"))
                .allMatch(entry -> Boolean.parseBoolean(values.getOrDefault(entry.getKey().replace(".attempts", ".allTokensAvailable"), "false")));
        return new WorkflowResult(runId, status, reason, attempts, inspection.policyState().usage(), reported, inspection.approval());
    }

    private static boolean isApprovalNode(WorkflowSpec spec, String node) {
        return spec.steps().stream().anyMatch(step -> step.type() == WorkflowStep.Type.APPROVAL && step.id().equals(node)
                || step.approvalBeforeApply() && (step.id() + ".approval").equals(node));
    }
    private RunInspection inspection(String runId) { return runs(null).inspect(runId); }
    private WorkflowSpec spec(String runId) { return PresetJson.decode(inspection(runId).state().values().get(SPEC), WorkflowSpec.class); }
    private GraphDefinition graph(String runId, WorkflowSpec spec) {
        return new WorkflowGraph(spec, project, runId, runtimes, new FileTool(project, runId, permissions, clock),
                new ProcessTool(project, runId, permissions, sandbox)).definition();
    }
    private ManagedRunService runs(WorkflowSpec spec) {
        PolicyConfiguration policy = spec == null ? PolicyConfiguration.unlimited()
                : new PolicyConfiguration(0, spec.maxElapsedMillis(), 0, 0, spec.maxUsage(), 0, 0);
        return new ManagedRunService(new GraphRunner(new GraphValidator()), new CheckpointStore(project, new CheckpointCodec()),
                new EventLogStore(project, new RunEventCodec()), new HandoffStore(project), new RunLockManager(project), policy, clock);
    }
}
