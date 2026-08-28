package io.ohmyluke.graph;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class GraphRunnerTest {
    private static final NodeId WRITE = new NodeId("write");
    private static final NodeId INSPECT = new NodeId("inspect");
    private static final NodeId END = new NodeId("end");

    private final GraphRunner runner = new GraphRunner(new GraphValidator());

    @Test
    void startsAtTheConfiguredNodeWithoutExecutingIt() {
        AtomicInteger executions = new AtomicInteger();
        Node first = node("first", context -> {
            executions.incrementAndGet();
            return NodeResult.success();
        });
        GraphDefinition graph = new GraphDefinition(
                first.id(),
                Set.of(first),
                List.of(new Edge(first.id(), END, Condition.always())),
                Set.of(END),
                0);

        RunState state = runner.start(graph, Map.of("request", "same"));

        assertEquals(RunStatus.RUNNING, state.status());
        assertEquals(first.id(), state.currentNode());
        assertEquals(0, state.executedSteps());
        assertEquals(List.of(first.id()), state.path());
        assertEquals(Map.of("request", "same"), state.values());
        assertEquals(0, executions.get());
    }

    @Test
    void stepExecutesExactlyOneNode() {
        AtomicInteger executions = new AtomicInteger();
        Node first = node("first", context -> {
            executions.incrementAndGet();
            return NodeResult.success(StatePatch.of("first", "done"));
        });
        Node second = node("second", context -> {
            executions.incrementAndGet();
            return NodeResult.success();
        });
        GraphDefinition graph = new GraphDefinition(
                first.id(),
                Set.of(first, second),
                List.of(
                        new Edge(first.id(), second.id(), Condition.always()),
                        new Edge(second.id(), END, Condition.always())),
                Set.of(END),
                0);

        RunState afterOneStep = runner.step(graph, runner.start(graph));

        assertEquals(RunStatus.RUNNING, afterOneStep.status());
        assertEquals(second.id(), afterOneStep.currentNode());
        assertEquals(1, afterOneStep.executedSteps());
        assertEquals(List.of(first.id(), second.id()), afterOneStep.path());
        assertEquals(Map.of("first", "done"), afterOneStep.values());
        assertEquals(1, executions.get());
    }

    @Test
    void resumedExecutionMatchesUninterruptedExecution() {
        GraphDefinition graph = retryUntilSecondAttemptGraph(10);

        RunState uninterrupted = runner.run(graph, Map.of("request", "same"));
        RunState interrupted = runner.step(graph, runner.step(graph, runner.start(
                graph,
                Map.of("request", "same"))));
        RunState resumed = runner.resume(graph, interrupted);

        assertEquals(uninterrupted, resumed);
    }

    @Test
    void completedStateIsNotExecutedAgain() {
        AtomicInteger executions = new AtomicInteger();
        Node only = node("only", context -> {
            executions.incrementAndGet();
            return NodeResult.success();
        });
        GraphDefinition graph = new GraphDefinition(
                only.id(),
                Set.of(only),
                List.of(new Edge(only.id(), END, Condition.always())),
                Set.of(END),
                0);
        RunState completed = runner.run(graph);

        RunState resumed = runner.resume(graph, completed);

        assertEquals(completed, resumed);
        assertEquals(1, executions.get());
    }

    @Test
    void executesLinearGraphUntilTerminalNode() {
        Node first = node("first", context -> NodeResult.success(StatePatch.of("first", "done")));
        Node second = node("second", context -> NodeResult.success(StatePatch.of("second", "done")));
        NodeId terminal = new NodeId("end");
        GraphDefinition graph = new GraphDefinition(
                first.id(),
                Set.of(first, second),
                List.of(
                        new Edge(first.id(), second.id(), Condition.always()),
                        new Edge(second.id(), terminal, Condition.always())),
                Set.of(terminal),
                0);

        RunState result = runner.run(graph);

        assertEquals(RunStatus.COMPLETED, result.status());
        assertEquals(List.of(first.id(), second.id(), terminal), result.path());
        assertEquals(Map.of("first", "done", "second", "done"), result.values());
        assertEquals(2, result.executedSteps());
    }

    @Test
    void repeatsFailedWorkAndFinishesWhenInspectionPasses() {
        GraphDefinition graph = retryUntilSecondAttemptGraph(10);

        RunState result = runner.run(graph);

        assertEquals(RunStatus.COMPLETED, result.status());
        assertEquals(List.of(WRITE, INSPECT, WRITE, INSPECT, END), result.path());
        assertEquals(Map.of("attempt", "2"), result.values());
        assertEquals(4, result.executedSteps());
        assertEquals("always", result.events().get(0).selectionReason());
        assertEquals("outcome == FAILURE", result.events().get(1).selectionReason());
        assertEquals("outcome == SUCCESS", result.events().get(3).selectionReason());
        assertEquals(Map.of("attempt", "1"), result.events().get(0).statePatch().updates());
    }

    @Test
    void sameInputProducesSamePathStateAndEvents() {
        GraphDefinition graph = retryUntilSecondAttemptGraph(10);

        RunState first = runner.run(graph, Map.of("request", "same"));
        RunState second = runner.run(graph, Map.of("request", "same"));

        assertEquals(first, second);
    }

    @Test
    void preservesStateIterationOrderAcrossNodeContextAndRunState() {
        List<String> observedOrder = new ArrayList<>();
        Map<String, String> patchUpdates = new LinkedHashMap<>();
        patchUpdates.put("patch-first", "first");
        patchUpdates.put("patch-second", "second");
        Node observer = node("observer", context -> {
            observedOrder.addAll(context.values().keySet());
            return NodeResult.success(new StatePatch(patchUpdates));
        });
        GraphDefinition graph = new GraphDefinition(
                observer.id(),
                Set.of(observer),
                List.of(new Edge(observer.id(), END, Condition.always())),
                Set.of(END),
                0);
        Map<String, String> initialValues = new LinkedHashMap<>();
        for (int index = 0; index < 32; index++) {
            initialValues.put("key-" + index, "value-" + index);
        }

        RunState result = runner.run(graph, initialValues);
        List<String> expectedFinalOrder = new ArrayList<>(initialValues.keySet());
        expectedFinalOrder.addAll(patchUpdates.keySet());

        assertEquals(List.copyOf(initialValues.keySet()), observedOrder);
        assertEquals(expectedFinalOrder, List.copyOf(result.values().keySet()));
        assertEquals(
                List.copyOf(patchUpdates.keySet()),
                List.copyOf(result.events().getFirst().statePatch().updates().keySet()));
        assertEquals(
                expectedFinalOrder,
                List.copyOf(result.events().getFirst().stateAfter().keySet()));
    }

    @Test
    void stopsSafelyWhenStepLimitIsReached() {
        Node writer = node("write", context -> NodeResult.success());
        Node inspector = node("inspect", context -> NodeResult.failure());
        GraphDefinition graph = new GraphDefinition(
                WRITE,
                Set.of(writer, inspector),
                List.of(
                        new Edge(WRITE, INSPECT, Condition.always()),
                        new Edge(INSPECT, END, Condition.outcomeIs(Outcome.SUCCESS)),
                        new Edge(INSPECT, WRITE, Condition.outcomeIs(Outcome.FAILURE)),
                        new Edge(INSPECT, END, Condition.outcomeIs(Outcome.SKIPPED)),
                        new Edge(INSPECT, END, Condition.outcomeIs(Outcome.CANCELLED))),
                Set.of(END),
                3);

        RunState result = runner.run(graph);

        assertEquals(RunStatus.STEP_LIMIT_REACHED, result.status());
        assertEquals(3, result.executedSteps());
        assertEquals(List.of(WRITE, INSPECT, WRITE, INSPECT), result.path());
        assertEquals(INSPECT, result.currentNode());
    }

    private static GraphDefinition retryUntilSecondAttemptGraph(int maxSteps) {
        Node writer = node("write", context -> {
            int attempt = Integer.parseInt(context.values().getOrDefault("attempt", "0")) + 1;
            return NodeResult.success(StatePatch.of("attempt", Integer.toString(attempt)));
        });
        Node inspector = node("inspect", context -> {
            int attempt = Integer.parseInt(context.values().get("attempt"));
            return attempt >= 2 ? NodeResult.success() : NodeResult.failure();
        });
        return new GraphDefinition(
                WRITE,
                Set.of(writer, inspector),
                List.of(
                        new Edge(WRITE, INSPECT, Condition.always()),
                        new Edge(INSPECT, END, Condition.outcomeIs(Outcome.SUCCESS)),
                        new Edge(INSPECT, WRITE, Condition.outcomeIs(Outcome.FAILURE)),
                        new Edge(INSPECT, END, Condition.outcomeIs(Outcome.SKIPPED)),
                        new Edge(INSPECT, END, Condition.outcomeIs(Outcome.CANCELLED))),
                Set.of(END),
                maxSteps);
    }

    private static Node node(String id, Function<NodeContext, NodeResult> action) {
        return new TestNode(new NodeId(id), action);
    }

    private record TestNode(NodeId id, Function<NodeContext, NodeResult> action) implements Node {
        @Override
        public NodeResult execute(NodeContext context) {
            return action.apply(context);
        }
    }
}
