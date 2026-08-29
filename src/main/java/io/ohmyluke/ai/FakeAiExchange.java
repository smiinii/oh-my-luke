package io.ohmyluke.ai;

import java.util.Objects;

/** One exact expected request and the deterministic result returned for it. */
public record FakeAiExchange(AiRequest expectedRequest, AiRuntimeResult result) {
    public FakeAiExchange {
        Objects.requireNonNull(expectedRequest, "expectedRequest");
        Objects.requireNonNull(result, "result");
    }
}
