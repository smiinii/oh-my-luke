package io.ohmyluke.graph;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class GraphValidatorTest {
    private static final NodeId A = new NodeId("a");
    private static final NodeId B = new NodeId("b");
    private static final NodeId END = new NodeId("end");

    private final GraphValidator validator = new GraphValidator();

    @Test
    void acceptsCycleWhenPositiveStepLimitExists() {
        GraphDefinition graph = cycleGraph(4);

        assertDoesNotThrow(() -> validator.validate(graph));
    }

    @Test
    void rejectsCycleWithoutStepLimitBeforeExecution() {
        GraphDefinition graph = cycleGraph(0);

        InvalidGraphException error = assertThrows(
                InvalidGraphException.class,
                () -> validator.validate(graph));

        assertTrue(error.problems().stream().anyMatch(problem -> problem.contains("step limit")));
    }

    @Test
    void rejectsMissingStartNode() {
        GraphDefinition graph = new GraphDefinition(
                new NodeId("missing"),
                Set.of(node("a")),
                List.of(new Edge(A, END, Condition.always())),
                Set.of(END),
                0);

        InvalidGraphException error = assertThrows(
                InvalidGraphException.class,
                () -> validator.validate(graph));

        assertTrue(error.problems().stream().anyMatch(problem -> problem.contains("start")));
    }

    @Test
    void rejectsEdgeToUnknownNode() {
        GraphDefinition graph = new GraphDefinition(
                A,
                Set.of(node("a")),
                List.of(new Edge(A, new NodeId("missing"), Condition.always())),
                Set.of(END),
                0);

        InvalidGraphException error = assertThrows(
                InvalidGraphException.class,
                () -> validator.validate(graph));

        assertTrue(error.problems().stream().anyMatch(problem -> problem.contains("unknown target")));
    }

    @Test
    void rejectsDuplicateNodeIds() {
        Node first = node("a");
        Node duplicate = node("a");
        GraphDefinition graph = new GraphDefinition(
                A,
                Set.of(first, duplicate),
                List.of(new Edge(A, END, Condition.always())),
                Set.of(END),
                0);

        InvalidGraphException error = assertThrows(
                InvalidGraphException.class,
                () -> validator.validate(graph));

        assertTrue(error.problems().stream().anyMatch(problem -> problem.contains("duplicate node id")));
    }

    @Test
    void rejectsOverlappingConditionsBeforeExecution() {
        GraphDefinition graph = new GraphDefinition(
                A,
                Set.of(node("a")),
                List.of(
                        new Edge(A, END, Condition.always()),
                        new Edge(A, END, Condition.outcomeIs(Outcome.SUCCESS))),
                Set.of(END),
                0);

        InvalidGraphException error = assertThrows(
                InvalidGraphException.class,
                () -> validator.validate(graph));

        assertTrue(error.problems().stream()
                .anyMatch(problem -> problem.contains("multiple edges can match node a for outcome SUCCESS")));
    }

    @Test
    void rejectsMissingOutcomeBranchBeforeExecution() {
        GraphDefinition graph = new GraphDefinition(
                A,
                Set.of(node("a")),
                List.of(new Edge(A, END, Condition.outcomeIs(Outcome.SUCCESS))),
                Set.of(END),
                0);

        InvalidGraphException error = assertThrows(
                InvalidGraphException.class,
                () -> validator.validate(graph));

        assertTrue(error.problems().stream()
                .anyMatch(problem -> problem.contains("no edge can match node a for outcome FAILURE")));
    }

    @Test
    void validatesVeryDeepAcyclicGraphWithoutOverflowingTheStack() {
        int nodeCount = 100_000;
        Set<Node> nodes = new LinkedHashSet<>();
        List<Edge> edges = new java.util.ArrayList<>();
        for (int index = 0; index < nodeCount; index++) {
            NodeId current = new NodeId("node-" + index);
            nodes.add(node(current.value()));
            NodeId next = index == nodeCount - 1 ? END : new NodeId("node-" + (index + 1));
            edges.add(new Edge(current, next, Condition.always()));
        }
        GraphDefinition graph = new GraphDefinition(
                new NodeId("node-0"),
                nodes,
                edges,
                Set.of(END),
                0);

        assertDoesNotThrow(() -> validator.validate(graph));
    }

    private static GraphDefinition cycleGraph(int maxSteps) {
        return new GraphDefinition(
                A,
                Set.of(node("a"), node("b")),
                List.of(
                        new Edge(A, B, Condition.always()),
                        new Edge(B, A, Condition.outcomeIs(Outcome.FAILURE)),
                        new Edge(B, END, Condition.outcomeIs(Outcome.SUCCESS)),
                        new Edge(B, END, Condition.outcomeIs(Outcome.SKIPPED)),
                        new Edge(B, END, Condition.outcomeIs(Outcome.CANCELLED))),
                Set.of(END),
                maxSteps);
    }

    private static Node node(String id) {
        return new TestNode(new NodeId(id), context -> NodeResult.success());
    }

    private static final class TestNode implements Node {
        private final NodeId id;
        private final Function<NodeContext, NodeResult> action;

        private TestNode(NodeId id, Function<NodeContext, NodeResult> action) {
            this.id = id;
            this.action = action;
        }

        @Override
        public NodeId id() {
            return id;
        }

        @Override
        public String fingerprint() {
            return "graph-validator-test-node-v1";
        }

        @Override
        public NodeResult execute(NodeContext context) {
            return action.apply(context);
        }
    }
}
