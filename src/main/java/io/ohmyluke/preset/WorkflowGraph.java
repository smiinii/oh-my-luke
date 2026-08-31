package io.ohmyluke.preset;

import io.ohmyluke.ai.AiRuntime;
import io.ohmyluke.graph.*;
import io.ohmyluke.tool.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/** Compiles static steps and inlined preset nodes into one ordinary graph. */
final class WorkflowGraph {
    private final WorkflowSpec spec;
    private final Path project;
    private final String runId;
    private final Function<TaskSpec, AiRuntime> runtimes;
    private final FileTool files;
    private final ProcessTool processes;
    private final String identity;
    private final Set<Node> nodes = new LinkedHashSet<>();
    private final List<Edge> edges = new ArrayList<>();

    WorkflowGraph(WorkflowSpec spec, Path project, String runId, Function<TaskSpec, AiRuntime> runtimes,
                  FileTool files, ProcessTool processes) {
        this.spec = spec;
        this.project = project;
        this.runId = runId;
        this.runtimes = runtimes;
        this.files = files;
        this.processes = processes;
        identity = "workflow:v1:" + PresetContentStore.hash(PresetJson.encode(spec).getBytes(StandardCharsets.UTF_8));
    }

    GraphDefinition definition() {
        for (WorkflowStep step : spec.steps()) {
            switch (step.type()) {
                case CHECK -> check(step);
                case EDIT -> edit(step);
                case APPROVAL -> {
                    nodes.add(new ApprovalNode(id(step.id()), step.prompt()));
                    routes(step.id(), target(step.onSuccess()), "stopped");
                }
            }
        }
        return new GraphDefinition(id(target(spec.start())), nodes, edges,
                Set.of(id("succeeded"), id("stopped")), spec.maxSteps());
    }

    private void check(WorkflowStep step) {
        TaskSpec task = new TaskSpec(1, spec.goal(), step.file(), ExecutionMode.DIRECT, 1,
                spec.maxUsage(), spec.maxElapsedMillis(), 1, step.validation(), null, null);
        AiRuntime noAi = new AiRuntime() {
            public String fingerprint() { return "workflow-check:no-ai:v1"; }
            public io.ohmyluke.ai.AiRuntimeResult invoke(io.ohmyluke.ai.AiRequest request) {
                throw new IllegalStateException("CHECK must not invoke AI");
            }
        };
        PresetGraph checker = new PresetGraph(task, noAi, files, processes, project, runId);
        nodes.add(node(step.id(), identity, context -> scopedResult(step, checker.check(local(step, context)))));
        routes(step.id(), target(step.onSuccess()), target(step.onFailure()));
    }

    private void edit(WorkflowStep step) {
        AiRuntime runtime = java.util.Objects.requireNonNull(runtimes.apply(step.task()), "runtime");
        GraphDefinition preset = new PresetGraph(step.task(), runtime, files, processes, project, runId).definition();
        for (Node delegate : preset.nodes()) {
            nodes.add(node(inline(step, delegate.id().value()), identity + ":" + delegate.fingerprint(),
                    context -> scopedResult(step, delegate.execute(local(step, context)))));
        }
        for (Edge edge : preset.edges()) {
            String to = inline(step, edge.to().value());
            if (step.approvalBeforeApply() && edge.from().value().equals("writer") && edge.to().value().equals("apply")) {
                to = step.id() + ".approval";
            }
            edges.add(new Edge(id(inline(step, edge.from().value())), id(to), edge.condition()));
        }
        if (step.approvalBeforeApply()) {
            String name = step.id() + ".approval";
            nodes.add(new ApprovalNode(id(name), "Apply the saved proposal to " + step.task().file() + " (step " + step.id() + ")?"));
            routes(name, step.id() + ".apply", "stopped");
        }
        nodes.add(node(step.id() + ".result", identity, context -> {
            Map<String, String> local = local(step, context).values();
            String status = local.getOrDefault("preset.status", "BLOCKED");
            Map<String, String> values = Map.of("workflow.status", status.equals("SUCCEEDED") ? "RUNNING" : status,
                    "workflow.reason", local.getOrDefault("preset.reason", "missing-edit-result"));
            if (status.equals("SUCCEEDED")) { return NodeResult.success(new StatePatch(values)); }
            if (status.equals("VALIDATION_FAILED")) { return NodeResult.failure(new StatePatch(values)); }
            return new NodeResult(Outcome.CANCELLED, new StatePatch(values));
        }));
        routes(step.id() + ".result", target(step.onSuccess()), target(step.onFailure()));
    }

    private NodeResult scopedResult(WorkflowStep step, NodeResult result) {
        Map<String, String> updates = new LinkedHashMap<>();
        result.statePatch().updates().forEach((key, value) -> updates.put(prefix(step) + key, value));
        String status = result.statePatch().updates().get("preset.status");
        String reason = result.statePatch().updates().get("preset.reason");
        if (status != null) { updates.put("workflow.status", status.equals("SUCCEEDED") ? "RUNNING" : status); }
        if (reason != null) { updates.put("workflow.reason", reason); }
        if (step.type() == WorkflowStep.Type.CHECK && ("BLOCKED".equals(status) || "LIMIT_REACHED".equals(status))) {
            // An inaccessible checker is not an ordinary false branch.
            return new NodeResult(Outcome.CANCELLED, new StatePatch(updates), null, result.metrics());
        }
        return new NodeResult(result.outcome(), new StatePatch(updates), result.failureInfo(), result.metrics());
    }

    private static NodeContext local(WorkflowStep step, NodeContext context) {
        Map<String, String> values = new LinkedHashMap<>();
        context.values().forEach((key, value) -> { if (key.startsWith(prefix(step))) { values.put(key.substring(prefix(step).length()), value); } });
        return new NodeContext(values, context.executedSteps(), context.runId());
    }

    private Node node(String name, String fingerprint, Action action) {
        return new Node() {
            public NodeId id() { return WorkflowGraph.id(name); }
            public String fingerprint() { return fingerprint + ":" + name; }
            public NodeResult execute(NodeContext context) {
                if (!context.explicitRunScope()) { throw new IllegalArgumentException("workflow requires managed run scope"); }
                try { return action.apply(context); }
                catch (Exception failure) {
                    return new NodeResult(Outcome.CANCELLED, new StatePatch(Map.of("workflow.status", "BLOCKED", "workflow.reason", "workflow-node-failed-safely")));
                }
            }
        };
    }

    private String target(String stepId) {
        return spec.steps().stream().filter(step -> step.id().equals(stepId)).findFirst()
                .map(step -> step.type() == WorkflowStep.Type.EDIT ? stepId + ".prepare" : stepId).orElse(stepId);
    }
    private static String inline(WorkflowStep step, String node) {
        return step.id() + "." + (node.equals("succeeded") || node.equals("stopped") ? "result" : node);
    }
    private void routes(String from, String yes, String no) {
        edges.add(new Edge(id(from), id(yes), Condition.outcomeIs(Outcome.SUCCESS)));
        edges.add(new Edge(id(from), id(no), Condition.outcomeIs(Outcome.FAILURE)));
        edges.add(new Edge(id(from), id("stopped"), Condition.outcomeIs(Outcome.SKIPPED)));
        edges.add(new Edge(id(from), id("stopped"), Condition.outcomeIs(Outcome.CANCELLED)));
    }
    private static String prefix(WorkflowStep step) { return "workflow.step." + step.id() + "."; }
    private static NodeId id(String value) { return new NodeId(value); }
    @FunctionalInterface private interface Action { NodeResult apply(NodeContext context) throws Exception; }
}
