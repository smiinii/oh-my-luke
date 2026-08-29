package io.ohmyluke.cli;

import io.ohmyluke.graph.GraphDefinition;
import io.ohmyluke.graph.RunState;
import io.ohmyluke.runtime.ManagedRunService;
import io.ohmyluke.runtime.RunInspection;
import io.ohmyluke.policy.PermissionMessages;
import io.ohmyluke.state.ProjectPermissionManager;
import java.io.PrintStream;
import java.util.Objects;

/** Minimal command surface for inspecting, cancelling, and resuming managed runs. */
public final class OmlukeCli {
    private final ManagedRunService runs;
    private final GraphResolver graphs;
    private final ProjectPermissionManager permissions;
    private final PrintStream out;
    private final PrintStream error;

    public OmlukeCli(
            ManagedRunService runs,
            GraphResolver graphs,
            ProjectPermissionManager permissions,
            PrintStream out,
            PrintStream error) {
        this.runs = Objects.requireNonNull(runs, "runs");
        this.graphs = Objects.requireNonNull(graphs, "graphs");
        this.permissions = Objects.requireNonNull(permissions, "permissions");
        this.out = Objects.requireNonNull(out, "out");
        this.error = Objects.requireNonNull(error, "error");
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
        return 0;
    }

    private int cancel(String runId) {
        RunState state = runs.cancel(runId);
        out.println("runId=" + runId);
        out.println("status=" + state.status());
        return 0;
    }

    private int resume(String runId) {
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
        target.println("사용법: omluke <inspect|cancel|resume> <run-id>");
        target.println("       omluke permissions <show|reset>");
        target.println("       omluke permissions autonomous <on|off>");
    }
}
