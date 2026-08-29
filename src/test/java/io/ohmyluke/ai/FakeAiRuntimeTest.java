package io.ohmyluke.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FakeAiRuntimeTest {
    @Test
    void returnsScriptedSuccessAndFailureByLogicalInvocation() {
        AiRequest first = request("plan:0", "plan", Map.of("goal", "ship"));
        AiRequest second = request("review:1", "review", Map.of("draft", "ready"));
        FakeAiRuntime runtime = new FakeAiRuntime(List.of(
                new FakeAiExchange(first, AiRuntimeResult.success("draft", 11)),
                new FakeAiExchange(second, AiRuntimeResult.failure(
                        "review.rejected", AiFailureReason.EXECUTION_FAILED, 7))));

        AiRuntimeResult success = runtime.invoke(first);
        AiRuntimeResult failure = runtime.invoke(second);

        assertEquals(AiRuntimeStatus.SUCCESS, success.status());
        assertEquals("draft", success.output());
        assertEquals(11, success.usage());
        assertEquals(AiRuntimeStatus.FAILURE, failure.status());
        assertEquals("review.rejected", failure.failure().code());
        assertEquals(7, failure.usage());
    }

    @Test
    void mismatchDoesNotConsumeTheExpectedResponse() {
        AiRequest expected = request("plan:0", "plan", Map.of("goal", "same"));
        FakeAiRuntime runtime = runtime(expected, AiRuntimeResult.success("done", 1));

        AiRuntimeResult mismatch = runtime.invoke(request("plan:0", "different", Map.of("goal", "same")));
        AiRuntimeResult retried = runtime.invoke(expected);

        assertEquals(AiRuntimeStatus.FAILURE, mismatch.status());
        assertEquals("fake.request-mismatch", mismatch.failure().code());
        assertEquals(0, mismatch.usage());
        assertEquals(AiRuntimeStatus.SUCCESS, retried.status());
    }

    @Test
    void replaysTheSameLogicalInvocationAndReportsUnknownInvocationAsExhausted() {
        AiRequest request = request("plan:0", "plan", Map.of());
        FakeAiRuntime runtime = runtime(request, AiRuntimeResult.success("done", 2));

        AiRuntimeResult first = runtime.invoke(request);
        AiRuntimeResult replayed = runtime.invoke(request);
        AiRuntimeResult firstExhausted = runtime.invoke(request("plan:1", "plan", Map.of()));
        AiRuntimeResult repeatedExhausted = runtime.invoke(request("plan:1", "plan", Map.of()));

        assertEquals(first, replayed);
        assertEquals(firstExhausted, repeatedExhausted);
        assertEquals("fake.script-exhausted", firstExhausted.failure().code());
    }

    @Test
    void requestContextIsImmutableCanonicalAndIndependentFromTheCaller() {
        LinkedHashMap<String, String> context = new LinkedHashMap<>();
        context.put("z", "last");
        context.put("a", "first");
        AiRequest request = request("plan:0", "plan", context);
        context.put("later", "mutation");

        assertEquals(List.of("a", "z"), List.copyOf(request.context().keySet()));
        assertEquals(Map.of("a", "first", "z", "last"), request.context());
        assertThrows(UnsupportedOperationException.class, () -> request.context().put("x", "y"));
    }

    @Test
    void fingerprintsDistinguishOrderRequestsResultsAndAmbiguousText() {
        AiRequest one = request("call:0", "a", Map.of("b", "c"));
        AiRequest two = request("call:1", "ab", Map.of("c", ""));
        FakeAiRuntime first = new FakeAiRuntime(List.of(
                new FakeAiExchange(one, AiRuntimeResult.success("x", 1)),
                new FakeAiExchange(two, AiRuntimeResult.success("y", 2))));
        FakeAiRuntime reordered = new FakeAiRuntime(List.of(
                new FakeAiExchange(two, AiRuntimeResult.success("y", 2)),
                new FakeAiExchange(one, AiRuntimeResult.success("x", 1))));
        FakeAiRuntime changedOutput = new FakeAiRuntime(List.of(
                new FakeAiExchange(one, AiRuntimeResult.success("different", 1)),
                new FakeAiExchange(two, AiRuntimeResult.success("y", 2))));

        assertEquals(first.fingerprint(), reordered.fingerprint());
        assertNotEquals(first.fingerprint(), changedOutput.fingerprint());
        assertEquals(first.fingerprint(), new FakeAiRuntime(List.of(
                new FakeAiExchange(one, AiRuntimeResult.success("x", 1)),
                new FakeAiExchange(two, AiRuntimeResult.success("y", 2)))).fingerprint());
    }

    @Test
    void aRecreatedRuntimeCanReplayALaterPersistedInvocation() {
        AiRequest first = request("plan:0", "plan", Map.of("attempt", "one"));
        AiRequest later = request("plan:2", "plan", Map.of("attempt", "two"));
        List<FakeAiExchange> script = List.of(
                new FakeAiExchange(first, AiRuntimeResult.success("first", 1)),
                new FakeAiExchange(later, AiRuntimeResult.success("later", 2)));

        AiRuntimeResult resumed = new FakeAiRuntime(script).invoke(later);

        assertEquals(AiRuntimeResult.success("later", 2), resumed);
    }

    @Test
    void modelsRejectContradictoryOrInvalidValues() {
        assertThrows(IllegalArgumentException.class, () -> request(" ", "plan", Map.of()));
        assertThrows(IllegalArgumentException.class, () -> AiRuntimeResult.success("done", -1));
        assertThrows(IllegalArgumentException.class, () -> new AiRuntimeResult(
                AiRuntimeStatus.SUCCESS,
                "done",
                new AiRuntimeFailure("bad", AiFailureReason.UNKNOWN),
                0));
        assertThrows(IllegalArgumentException.class, () -> new AiRuntimeResult(
                AiRuntimeStatus.FAILURE,
                "unexpected output",
                new AiRuntimeFailure("bad", AiFailureReason.UNKNOWN),
                0));
        assertThrows(IllegalArgumentException.class, () -> new AiRuntimeFailure(
                "Bearer secret-token", AiFailureReason.UNKNOWN));
        AiRequest duplicate = request("duplicate", "plan", Map.of());
        assertThrows(IllegalArgumentException.class, () -> new FakeAiRuntime(List.of(
                new FakeAiExchange(duplicate, AiRuntimeResult.success("one", 1)),
                new FakeAiExchange(duplicate, AiRuntimeResult.success("two", 1)))));
    }

    private static FakeAiRuntime runtime(AiRequest request, AiRuntimeResult result) {
        return new FakeAiRuntime(List.of(new FakeAiExchange(request, result)));
    }

    private static AiRequest request(String id, String instruction, Map<String, String> context) {
        return new AiRequest(id, instruction, context);
    }
}
