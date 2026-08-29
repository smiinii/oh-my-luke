package io.ohmyluke.ai;

import java.util.List;
import java.util.Objects;

/** In-memory strict script used to test AI flows without a model, process, or network. */
public final class FakeAiRuntime implements AiRuntime {
    private final List<FakeAiExchange> exchanges;
    private final String fingerprint;
    private int cursor;

    public FakeAiRuntime(List<FakeAiExchange> exchanges) {
        this.exchanges = List.copyOf(Objects.requireNonNull(exchanges, "exchanges"));
        this.fingerprint = "fake-ai:v1:sha256:" + AiFingerprints.fakeRuntime(this.exchanges);
    }

    @Override
    public String fingerprint() {
        return fingerprint;
    }

    @Override
    public synchronized AiRuntimeResult invoke(AiRequest request) {
        Objects.requireNonNull(request, "request");
        if (cursor >= exchanges.size()) {
            return AiRuntimeResult.failure(
                    "fake.script-exhausted",
                    "no scripted response remains",
                    0);
        }
        FakeAiExchange next = exchanges.get(cursor);
        if (!next.expectedRequest().equals(request)) {
            return AiRuntimeResult.failure(
                    "fake.request-mismatch",
                    "request did not match scripted exchange " + cursor,
                    0);
        }
        cursor++;
        return next.result();
    }

    public synchronized int consumedResponses() {
        return cursor;
    }
}
