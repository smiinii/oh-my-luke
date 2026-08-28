package io.ohmyluke.cli;

import io.ohmyluke.graph.GraphDefinition;
import io.ohmyluke.graph.RunState;
import io.ohmyluke.runtime.ManagedRunService;
import io.ohmyluke.runtime.RunInspection;
import java.io.PrintStream;
import java.util.Objects;

/** Minimal command surface for inspecting, cancelling, and resuming managed runs. */
public final class OmlukeCli {
    private final ManagedRunService runs;
    private final GraphResolver graphs;
    private final PrintStream out;
    private final PrintStream error;

    public OmlukeCli(
            ManagedRunService runs,
            GraphResolver graphs,
            PrintStream out,
            PrintStream error) {
        this.runs = Objects.requireNonNull(runs, "runs");
        this.graphs = Objects.requireNonNull(graphs, "graphs");
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
        if (args.length != 2) {
            printUsage(error);
            return 2;
        }
        try {
            return switch (args[0]) {
                case "inspect" -> inspect(args[1]);
                case "cancel" -> cancel(args[1]);
                case "resume" -> resume(args[1]);
                default -> {
                    printUsage(error);
                    yield 2;
                }
            };
        } catch (RuntimeException failure) {
            error.println("오류: " + failure.getMessage());
            return 1;
        }
    }

    private int inspect(String runId) {
        RunInspection inspection = runs.inspect(runId);
        out.println("runId=" + inspection.runId());
        out.println("status=" + inspection.state().status());
        out.println("phase=" + inspection.phase());
        out.println("currentNode=" + inspection.state().currentNode().value());
        out.println("executedSteps=" + inspection.state().executedSteps());
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
    }
}
