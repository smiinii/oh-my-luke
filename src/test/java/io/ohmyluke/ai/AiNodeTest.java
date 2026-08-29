package io.ohmyluke.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.ohmyluke.graph.Condition;
import io.ohmyluke.graph.Edge;
import io.ohmyluke.graph.GraphDefinition;
import io.ohmyluke.graph.GraphRunner;
import io.ohmyluke.graph.GraphValidator;
import io.ohmyluke.graph.NodeContext;
import io.ohmyluke.graph.NodeId;
import io.ohmyluke.graph.NodeResult;
import io.ohmyluke.graph.Outcome;
import io.ohmyluke.graph.RunState;
import io.ohmyluke.graph.RunStatus;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AiNodeTest {
    private static final NodeId PLAN = new NodeId("plan");
    private static final NodeId END = new NodeId("end");

    @Test
    void writesSuccessfulOutputAndReportsUsageWithoutAToolCall() {
        AiRequest expected = new AiRequest("plan:0", "Create a plan", Map.of("goal", "ship"));
        FakeAiRuntime runtime = runtime(expected, AiRuntimeResult.success("step one", 13));
        AiNode node = node(runtime, List.of("goal"));

        NodeResult result = node.execute(new NodeContext(Map.of("goal", "ship", "secret", "hidden"), 0));

        assertEquals(Outcome.SUCCESS, result.outcome());
        assertEquals(Map.of("ai.plan.output", "step one"), result.statePatch().updates());
        assertEquals(0, result.metrics().toolCalls());
        assertEquals(13, result.metrics().usage());
        assertEquals(1, runtime.consumedResponses());
    }

    @Test
    void convertsRuntimeFailureToStableGraphFailureAndKeepsUsage() {
        AiRequest expected = new AiRequest("plan:3", "Create a plan", Map.of("goal", "ship"));
        FakeAiRuntime runtime = runtime(
                expected,
                AiRuntimeResult.failure("fake.model-error", "scripted model failure", 5));
        AiNode node = node(runtime, List.of("goal"));

        NodeResult result = node.execute(new NodeContext(Map.of("goal", "ship"), 3));

        assertEquals(Outcome.FAILURE, result.outcome());
        assertEquals("ai-runtime", result.failureInfo().type());
        assertEquals("fake.model-error", result.failureInfo().code());
        assertEquals("scripted model failure", result.failureInfo().cause());
        assertEquals(5, result.metrics().usage());
        assertEquals(Map.of(), result.statePatch().updates());
    }

    @Test
    void missingSelectedStateFailsBeforeInvokingTheRuntime() {
        AiRequest expected = new AiRequest("plan:0", "Create a plan", Map.of("goal", "ship"));
        FakeAiRuntime runtime = runtime(expected, AiRuntimeResult.success("unused", 99));
        AiNode node = node(runtime, List.of("goal", "project"));

        NodeResult result = node.execute(new NodeContext(Map.of("goal", "ship"), 0));

        assertEquals(Outcome.FAILURE, result.outcome());
        assertEquals("ai-input", result.failureInfo().type());
        assertEquals("missing-state", result.failureInfo().code());
        assertEquals(0, result.metrics().usage());
        assertEquals(0, runtime.consumedResponses());
    }

    @Test
    void graphRunsThroughTheFakeRuntimeWithoutAiOrNetwork() {
        GraphDefinition firstGraph = graph(runtime(
                new AiRequest("plan:0", "Create a plan", Map.of("goal", "ship")),
                AiRuntimeResult.success("verified plan", 8)));
        GraphDefinition secondGraph = graph(runtime(
                new AiRequest("plan:0", "Create a plan", Map.of("goal", "ship")),
                AiRuntimeResult.success("verified plan", 8)));
        GraphRunner runner = new GraphRunner(new GraphValidator());

        RunState first = runner.run(firstGraph, Map.of("goal", "ship"));
        RunState second = runner.run(secondGraph, Map.of("goal", "ship"));

        assertEquals(first, second);
        assertEquals(RunStatus.COMPLETED, first.status());
        assertEquals(List.of(PLAN, END), first.path());
        assertEquals("verified plan", first.values().get("ai.plan.output"));
        assertEquals(8, first.events().getFirst().metrics().usage());
    }

    @Test
    void nodeFingerprintCoversRuntimeInstructionKeysAndOutputKey() {
        AiRequest expected = new AiRequest("plan:0", "Create a plan", Map.of("a", "1", "bc", "2"));
        FakeAiRuntime firstRuntime = runtime(expected, AiRuntimeResult.success("one", 1));
        FakeAiRuntime changedRuntime = runtime(expected, AiRuntimeResult.success("two", 1));
        AiNode first = new AiNode(PLAN, firstRuntime, "Create a plan", List.of("a", "bc"), "output");
        AiNode reorderedSameKeys = new AiNode(
                PLAN, firstRuntime, "Create a plan", List.of("bc", "a"), "output");
        AiNode ambiguousKeys = new AiNode(PLAN, firstRuntime, "Create a plan", List.of("ab", "c"), "output");
        AiNode changedInstruction = new AiNode(PLAN, firstRuntime, "Review a plan", List.of("a", "bc"), "output");
        AiNode changedOutput = new AiNode(PLAN, firstRuntime, "Create a plan", List.of("a", "bc"), "other");
        AiNode changedScript = new AiNode(PLAN, changedRuntime, "Create a plan", List.of("a", "bc"), "output");

        assertEquals(first.fingerprint(), reorderedSameKeys.fingerprint());
        assertNotEquals(first.fingerprint(), ambiguousKeys.fingerprint());
        assertNotEquals(first.fingerprint(), changedInstruction.fingerprint());
        assertNotEquals(first.fingerprint(), changedOutput.fingerprint());
        assertNotEquals(first.fingerprint(), changedScript.fingerprint());
    }

    @Test
    void nodeRejectsDuplicateOrBlankConfiguration() {
        FakeAiRuntime runtime = new FakeAiRuntime(List.of());

        assertThrows(IllegalArgumentException.class, () -> new AiNode(
                PLAN, runtime, "Create", List.of("goal", "goal"), "output"));
        assertThrows(IllegalArgumentException.class, () -> new AiNode(
                PLAN, runtime, " ", List.of(), "output"));
        assertThrows(IllegalArgumentException.class, () -> new AiNode(
                PLAN, runtime, "Create", List.of(), " "));
    }

    private static AiNode node(FakeAiRuntime runtime, List<String> keys) {
        return new AiNode(PLAN, runtime, "Create a plan", keys, "ai.plan.output");
    }

    private static FakeAiRuntime runtime(AiRequest request, AiRuntimeResult result) {
        return new FakeAiRuntime(List.of(new FakeAiExchange(request, result)));
    }

    private static GraphDefinition graph(FakeAiRuntime runtime) {
        AiNode node = node(runtime, List.of("goal"));
        return new GraphDefinition(
                PLAN,
                Set.of(node),
                List.of(new Edge(PLAN, END, Condition.always())),
                Set.of(END),
                0);
    }
}
