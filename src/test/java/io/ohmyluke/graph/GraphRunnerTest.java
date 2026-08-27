package io.ohmyluke.graph;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class GraphRunnerTest {
    private static final NodeId WRITE = new NodeId("write");
    private static final NodeId INSPECT = new NodeId("inspect");
    private static final NodeId END = new NodeId("end");

    private final GraphRunner runner = new GraphRunner(new GraphValidator());

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
    void stopsSafelyWhenStepLimitIsReached() {
        Node writer = node("write", context -> NodeResult.success());
        Node inspector = node("inspect", context -> NodeResult.failure());
        GraphDefinition graph = new GraphDefinition(
                WRITE,
                Set.of(writer, inspector),
                List.of(
                        new Edge(WRITE, INSPECT, Condition.always()),
                        new Edge(INSPECT, END, Condition.outcomeIs(Outcome.SUCCESS)),
                        new Edge(INSPECT, WRITE, Condition.outcomeIs(Outcome.FAILURE))),
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
                        new Edge(INSPECT, WRITE, Condition.outcomeIs(Outcome.FAILURE))),
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
