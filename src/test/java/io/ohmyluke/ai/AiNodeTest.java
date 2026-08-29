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
        AiRequest expected = request("test-run", 0, "Create a plan", Map.of("goal", "ship"));
        FakeAiRuntime runtime = runtime(expected, AiRuntimeResult.success("step one", 13));
        AiNode node = node(runtime, List.of("goal"));

        NodeResult result = node.execute(new NodeContext(
                Map.of("goal", "ship", "secret", "hidden"), 0, "test-run"));

        assertEquals(Outcome.SUCCESS, result.outcome());
        assertEquals(Map.of("ai.plan.output", "step one"), result.statePatch().updates());
        assertEquals(0, result.metrics().toolCalls());
        assertEquals(13, result.metrics().usage());
    }

    @Test
    void convertsRuntimeFailureToStableGraphFailureAndKeepsUsage() {
        AiRequest expected = request("test-run", 3, "Create a plan", Map.of("goal", "ship"));
        FakeAiRuntime runtime = runtime(
                expected,
                AiRuntimeResult.failure(AiFailureCode.EXECUTION_FAILED, 5));
        AiNode node = node(runtime, List.of("goal"));

        NodeResult result = node.execute(new NodeContext(Map.of("goal", "ship"), 3, "test-run"));

        assertEquals(Outcome.FAILURE, result.outcome());
        assertEquals("ai-runtime", result.failureInfo().type());
        assertEquals("runtime.execution-failed", result.failureInfo().code());
        assertEquals("AI runtime execution failed", result.failureInfo().cause());
        assertEquals(5, result.metrics().usage());
        assertEquals(Map.of(), result.statePatch().updates());
    }

    @Test
    void missingSelectedStateFailsBeforeInvokingTheRuntime() {
        AiRequest expected = request("test-run", 0, "Create a plan", Map.of("goal", "ship"));
        FakeAiRuntime runtime = runtime(expected, AiRuntimeResult.success("unused", 99));
        AiNode node = node(runtime, List.of("goal", "project"));

        NodeResult result = node.execute(new NodeContext(Map.of("goal", "ship"), 0, "test-run"));

        assertEquals(Outcome.FAILURE, result.outcome());
        assertEquals("ai-input", result.failureInfo().type());
        assertEquals("missing-state", result.failureInfo().code());
        assertEquals(0, result.metrics().usage());
    }

    @Test
    void graphRunsThroughTheFakeRuntimeWithoutAiOrNetwork() {
        GraphDefinition graph = graph(runtime(
                request("test-run", 0, "Create a plan", Map.of("goal", "ship")),
                AiRuntimeResult.success("verified plan", 8)));
        GraphRunner runner = new GraphRunner(new GraphValidator());

        RunState first = runner.run(graph, Map.of("goal", "ship"), "test-run");
        RunState second = runner.run(graph, Map.of("goal", "ship"), "test-run");

        assertEquals(first, second);
        assertEquals(RunStatus.COMPLETED, first.status());
        assertEquals(List.of(PLAN, END), first.path());
        assertEquals("verified plan", first.values().get("ai.plan.output"));
        assertEquals(8, first.events().getFirst().metrics().usage());
    }

    @Test
    void defaultGraphScopeCannotInvokeAnAiRuntime() {
        FakeAiRuntime runtime = runtime(
                request("local", 0, "Create a plan", Map.of("goal", "ship")),
                AiRuntimeResult.success("must not be used", 99));
        GraphDefinition graph = graph(runtime);

        GraphRunner runner = new GraphRunner(new GraphValidator());
        RunState result = runner.run(
                graph,
                Map.of("goal", "ship"));
        RunState explicitlyNamedLocal = runner.run(
                graph,
                Map.of("goal", "ship"),
                "local");

        assertEquals(Outcome.FAILURE, result.events().getFirst().outcome());
        assertEquals("missing-run-scope", result.events().getFirst().failure().code());
        assertEquals(0, result.events().getFirst().metrics().usage());
        assertEquals(Outcome.SUCCESS, explicitlyNamedLocal.events().getFirst().outcome());
        assertEquals("must not be used", explicitlyNamedLocal.values().get("ai.plan.output"));
    }

    @Test
    void oneRuntimeSeparatesRunsAndReplaysTheSameRunRetry() {
        AiRequest firstRequest = request("run-a", 0, "Create a plan", Map.of("goal", "ship"));
        AiRequest secondRequest = request("run-b", 0, "Create a plan", Map.of("goal", "ship"));
        FakeAiRuntime runtime = new FakeAiRuntime(List.of(
                new FakeAiExchange(firstRequest, AiRuntimeResult.success("plan a", 2)),
                new FakeAiExchange(secondRequest, AiRuntimeResult.success("plan b", 3))));
        GraphDefinition graph = graph(runtime);
        GraphRunner runner = new GraphRunner(new GraphValidator());

        RunState first = runner.run(graph, Map.of("goal", "ship"), "run-a");
        RunState second = runner.run(graph, Map.of("goal", "ship"), "run-b");
        RunState retried = runner.run(graph, Map.of("goal", "ship"), "run-a");

        assertEquals("plan a", first.values().get("ai.plan.output"));
        assertEquals("plan b", second.values().get("ai.plan.output"));
        assertEquals(first, retried);
        assertNotEquals(firstRequest.invocationId(), secondRequest.invocationId());
    }

    @Test
    void nodeFingerprintCoversRuntimeInstructionKeysAndOutputKey() {
        AiRequest expected = request(
                "test-run", 0, "Create a plan", Map.of("a", "1", "bc", "2"));
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

    private static AiRequest request(
            String runId,
            int executedSteps,
            String instruction,
            Map<String, String> context) {
        return new AiRequest(
                AiInvocationId.forNode(runId, PLAN, executedSteps),
                instruction,
                context);
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
