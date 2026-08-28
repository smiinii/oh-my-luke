package io.ohmyluke.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import io.ohmyluke.graph.Condition;
import io.ohmyluke.graph.Edge;
import io.ohmyluke.graph.GraphDefinition;
import io.ohmyluke.graph.Node;
import io.ohmyluke.graph.NodeContext;
import io.ohmyluke.graph.NodeId;
import io.ohmyluke.graph.NodeResult;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class GraphSignatureTest {
    @Test
    void sameStructureProducesSameSignatureRegardlessOfSetOrder() {
        GraphDefinition first = graph(Set.of(node("a"), node("b")), 5);
        GraphDefinition second = graph(Set.of(node("b"), node("a")), 5);

        assertEquals(GraphSignature.calculate(first), GraphSignature.calculate(second));
    }

    @Test
    void structuralChangeProducesDifferentSignature() {
        GraphDefinition first = graph(Set.of(node("a"), node("b")), 5);
        GraphDefinition changed = graph(Set.of(node("a"), node("b")), 6);

        assertNotEquals(GraphSignature.calculate(first), GraphSignature.calculate(changed));
    }

    private static GraphDefinition graph(Set<Node> nodes, int maxSteps) {
        NodeId a = new NodeId("a");
        NodeId b = new NodeId("b");
        NodeId end = new NodeId("end");
        return new GraphDefinition(
                a,
                nodes,
                List.of(
                        new Edge(a, b, Condition.always()),
                        new Edge(b, end, Condition.always())),
                Set.of(end),
                maxSteps);
    }

    private static Node node(String value) {
        return new TestNode(new NodeId(value));
    }

    private record TestNode(NodeId id) implements Node {
        @Override
        public NodeResult execute(NodeContext context) {
            return NodeResult.success();
        }
    }
}
