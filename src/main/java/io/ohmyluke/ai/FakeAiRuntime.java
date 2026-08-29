package io.ohmyluke.ai;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/** In-memory strict script used to test AI flows without a model, process, or network. */
public final class FakeAiRuntime implements AiRuntime {
    private final Map<String, FakeAiExchange> exchangesByInvocation;
    private final String fingerprint;

    public FakeAiRuntime(List<FakeAiExchange> exchanges) {
        List<FakeAiExchange> copy = List.copyOf(Objects.requireNonNull(exchanges, "exchanges"));
        try {
            this.exchangesByInvocation = copy.stream().collect(Collectors.toUnmodifiableMap(
                    exchange -> exchange.expectedRequest().invocationId(),
                    Function.identity()));
        } catch (IllegalStateException duplicate) {
            throw new IllegalArgumentException(
                    "exchanges must have unique invocation ids",
                    duplicate);
        }
        this.fingerprint = "fake-ai:v2:sha256:" + AiFingerprints.fakeRuntime(copy);
    }

    @Override
    public String fingerprint() {
        return fingerprint;
    }

    @Override
    public AiRuntimeResult invoke(AiRequest request) {
        Objects.requireNonNull(request, "request");
        FakeAiExchange exchange = exchangesByInvocation.get(request.invocationId());
        if (exchange == null) {
            return AiRuntimeResult.failure(
                    "fake.script-exhausted",
                    AiFailureReason.SCRIPT_EXHAUSTED,
                    0);
        }
        if (!exchange.expectedRequest().equals(request)) {
            return AiRuntimeResult.failure(
                    "fake.request-mismatch",
                    AiFailureReason.SCRIPT_MISMATCH,
                    0);
        }
        return exchange.result();
    }
}
