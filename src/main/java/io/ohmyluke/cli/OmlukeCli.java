package io.ohmyluke.cli;

import io.ohmyluke.graph.GraphDefinition;
import io.ohmyluke.graph.RunState;
import io.ohmyluke.runtime.ManagedRunService;
import io.ohmyluke.runtime.RunInspection;
import io.ohmyluke.policy.PermissionMessages;
import io.ohmyluke.state.ProjectPermissionManager;
import io.ohmyluke.preset.PresetRunService;
import io.ohmyluke.preset.PresetResult;
import io.ohmyluke.preset.TaskSpec;
import io.ohmyluke.preset.WorkflowResult;
import io.ohmyluke.preset.WorkflowRunService;
import io.ohmyluke.preset.WorkflowSpec;
import io.ohmyluke.preset.WorkflowStatus;
import io.ohmyluke.state.ApprovalDecision;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Objects;

/** Minimal command surface for inspecting, cancelling, and resuming managed runs. */
public final class OmlukeCli {
    private final ManagedRunService runs;
    private final GraphResolver graphs;
    private final ProjectPermissionManager permissions;
    private final PrintStream out;
    private final PrintStream error;
    private final PresetRunService presets;
    private final WorkflowRunService workflows;

    public OmlukeCli(
            ManagedRunService runs,
            GraphResolver graphs,
            ProjectPermissionManager permissions,
            PrintStream out,
            PrintStream error) {
        this(runs, graphs, permissions, out, error, null);
    }

    public OmlukeCli(ManagedRunService runs, GraphResolver graphs, ProjectPermissionManager permissions,
                     PrintStream out, PrintStream error, PresetRunService presets) {
        this(runs, graphs, permissions, out, error, presets, null);
    }

    public OmlukeCli(ManagedRunService runs, GraphResolver graphs, ProjectPermissionManager permissions,
                     PrintStream out, PrintStream error, PresetRunService presets,
                     WorkflowRunService workflows) {
        this.runs = Objects.requireNonNull(runs, "runs");
        this.graphs = Objects.requireNonNull(graphs, "graphs");
        this.permissions = Objects.requireNonNull(permissions, "permissions");
        this.out = Objects.requireNonNull(out, "out");
        this.error = Objects.requireNonNull(error, "error");
        this.presets = presets;
        this.workflows = workflows;
    }

    public int execute(String[] args) {
        Objects.requireNonNull(args, "args");
        if (args.length == 0) {
            out.println(OmlukeApplication.productName());
            printUsage(out);
            return 0;
        }
        try {
            if (args[0].equals("permissions")) {
                return permissions(args);
            }
            if (args[0].equals("run") && presets != null) {
                return run(args);
            }
            if (args[0].equals("workflow") && workflows != null) {
                return workflow(args);
            }
            if (args.length == 3 && workflows != null
                    && (args[0].equals("approve") || args[0].equals("deny"))) {
                return decideApproval(args[1], args[2], args[0].equals("approve"));
            }
            if (args.length == 2) {
                return switch (args[0]) {
                    case "inspect" -> inspect(args[1]);
                    case "cancel" -> cancel(args[1]);
                    case "resume" -> resume(args[1]);
                    default -> usageError();
                };
            }
            return usageError();
        } catch (RuntimeException failure) {
            error.println("오류: " + failure.getMessage());
            return 1;
        }
    }

    private int run(String[] args) {
        if (args.length < 2 || args.length % 2 != 0) { return usageError(); }
        String runId = "run-" + java.util.UUID.randomUUID();
        String model = null;
        String reasoning = null;
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 2; i < args.length; i += 2) {
            if (!seen.add(args[i])) { return usageError(); }
            switch (args[i]) {
                case "--run-id" -> runId = args[i + 1];
                case "--model" -> model = args[i + 1];
                case "--reasoning" -> reasoning = args[i + 1];
                default -> { return usageError(); }
            }
        }
        TaskSpec task = presets.readTask(Path.of(args[1])).withRuntimeSelection(model, reasoning);
        presets.start(runId, task);
        out.println("runId=" + runId); // visible before a potentially long runtime call
        out.println("mode=" + task.mode());
        out.println("model=" + (task.model() == null ? "inherit" : task.model()));
        out.println("reasoning=" + (task.reasoning() == null ? "inherit" : task.reasoning()));
        return printPreset(presets.resume(runId));
    }

    private int printPreset(PresetResult result) {
        out.println("result=" + result.status());
        out.println("reason=" + result.reason());
        out.println("aiAttempts=" + result.attempts());
        out.println("recordedUsage=" + result.recordedUsage());
        out.println("allTokenUsageAvailable=" + result.allTokenUsageAvailable());
        return result.exitCode();
    }

    private int workflow(String[] args) {
        if (args.length < 2 || args.length % 2 != 0) { return usageError(); }
        String runId = "run-" + java.util.UUID.randomUUID();
        String model = null;
        String reasoning = null;
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 2; i < args.length; i += 2) {
            if (!seen.add(args[i])) { return usageError(); }
            switch (args[i]) {
                case "--run-id" -> runId = args[i + 1];
                case "--model" -> model = args[i + 1];
                case "--reasoning" -> reasoning = args[i + 1];
                default -> { return usageError(); }
            }
        }
        WorkflowSpec spec = workflows.readSpec(Path.of(args[1])).withRuntimeSelection(model, reasoning);
        workflows.start(runId, spec);
        out.println("runId=" + runId);
        out.println("mode=WORKFLOW");
        out.println("modelOverride=" + (model == null ? "inherit-per-step" : model));
        out.println("reasoningOverride=" + (reasoning == null ? "inherit-per-step" : reasoning));
        return printWorkflow(workflows.resume(runId));
    }

    private int printWorkflow(WorkflowResult result) {
        out.println("result=" + result.status());
        out.println("reason=" + result.reason());
        out.println("aiAttempts=" + result.attempts());
        out.println("recordedUsage=" + result.recordedUsage());
        out.println("allTokenUsageAvailable=" + result.allTokenUsageAvailable());
        if (result.approval() != null) {
            out.println("approvalRequestId=" + result.approval().requestId());
            out.println("approvalNode=" + result.approval().node().value());
            out.println("approvalPrompt=" + result.approval().prompt());
            out.println("approvalDecision=" + result.approval().decision());
            out.println("워크플로 단계 진행 승인입니다. 파일·명령 실행 도구 권한을 부여하지 않습니다.");
            if (result.status() == WorkflowStatus.WAITING_APPROVAL
                    && result.approval().decision() == ApprovalDecision.PENDING) {
                out.println("승인: omluke approve " + result.runId() + " " + result.approval().requestId());
                out.println("거부: omluke deny " + result.runId() + " " + result.approval().requestId());
            }
        }
        return result.exitCode();
    }

    private int decideApproval(String runId, String requestId, boolean approved) {
        if (!workflows.supports(runId)) {
            throw new IllegalStateException("승인할 워크플로 실행을 찾지 못했습니다: " + runId);
        }
        WorkflowResult result = workflows.decideApproval(runId, requestId, approved);
        out.println("runId=" + runId);
        printWorkflow(result);
        out.println("승인 결정을 저장했습니다. 다음 단계는 아직 실행하지 않았습니다.");
        out.println("재개: omluke resume " + runId);
        return 0;
    }

    private int permissions(String[] args) {
        if (args.length == 2 && args[1].equals("show")) {
            out.println("autonomousProject=" + permissions.settings().autonomousProject());
            out.println("rememberedGrants=" + permissions.settings().grants().size());
            return 0;
        }
        if (args.length == 2 && args[1].equals("reset")) {
            permissions.reset();
            out.println("저장된 승인과 프로젝트 자율 실행을 초기화했습니다.");
            return 0;
        }
        if (args.length == 3 && args[1].equals("autonomous")) {
            if (args[2].equals("on")) {
                permissions.setAutonomousProject(true);
                out.println(PermissionMessages.autonomousEnabled());
                return 0;
            }
            if (args[2].equals("off")) {
                permissions.setAutonomousProject(false);
                out.println("프로젝트 자율 실행을 해제했습니다. 필요한 작업은 다시 승인을 요청합니다.");
                return 0;
            }
        }
        return usageError();
    }

    private int usageError() {
        printUsage(error);
        return 2;
    }

    private int inspect(String runId) {
        RunInspection inspection = runs.inspect(runId);
        out.println("runId=" + inspection.runId());
        out.println("status=" + inspection.state().status());
        out.println("phase=" + inspection.phase());
        out.println("currentNode=" + inspection.state().currentNode().value());
        out.println("executedSteps=" + inspection.state().executedSteps());
        out.println("policyOutcome=" + inspection.policyState().lastDecision().outcome());
        out.println("policyReason=" + inspection.policyState().lastDecision().reasonCode());
        out.println("policyResumable=" + inspection.policyState().lastDecision().resumable());
        out.println("iterations=" + inspection.policyState().iterations());
        out.println("nodeCalls=" + inspection.policyState().nodeCalls());
        out.println("toolCalls=" + inspection.policyState().toolCalls());
        out.println("usage=" + inspection.policyState().usage());
        out.println("repeatedFailureCount=" + inspection.policyState().repeatedFailureCount());
        out.println("noProgressCount=" + inspection.policyState().noProgressCount());
        out.println("events=" + inspection.events().size());
        out.println("recoveredFromBackup=" + inspection.recoveredFromBackup());
        out.println("ignoredIncompleteEventTail=" + inspection.ignoredIncompleteEventTail());
        if (workflows != null && workflows.supports(runId)) {
            printWorkflow(workflows.inspect(runId));
        } else if (presets != null && presets.supports(runId)) {
            printPreset(presets.inspect(runId));
        }
        return 0;
    }

    private int cancel(String runId) {
        RunState state = runs.cancel(runId);
        out.println("runId=" + runId);
        out.println("status=" + state.status());
        return 0;
    }

    private int resume(String runId) {
        if (workflows != null && workflows.supports(runId)) {
            out.println("runId=" + runId);
            return printWorkflow(workflows.resume(runId));
        }
        if (presets != null && presets.supports(runId)) {
            out.println("runId=" + runId);
            return printPreset(presets.resume(runId));
        }
        RunInspection inspection = runs.inspect(runId);
        GraphDefinition graph = graphs.resolve(inspection.graphSignature())
                .orElseThrow(() -> new IllegalStateException(
                        "저장된 그래프를 실행할 제공자를 찾지 못했습니다: "
                                + inspection.graphSignature()));
        RunState state = runs.resume(runId, graph);
        out.println("runId=" + runId);
        out.println("status=" + state.status());
        out.println("executedSteps=" + state.executedSteps());
        return 0;
    }

    private static void printUsage(PrintStream target) {
        target.println("사용법: omluke run <task.json> [--run-id ID] [--model MODEL] [--reasoning LEVEL]");
        target.println("사용법: omluke workflow <workflow.json> [--run-id ID] [--model MODEL] [--reasoning LEVEL]");
        target.println("사용법: omluke <inspect|cancel|resume> <run-id>");
        target.println("       omluke <approve|deny> <run-id> <request-id>");
        target.println("       omluke permissions <show|reset>");
        target.println("       omluke permissions autonomous <on|off>");
    }
}
