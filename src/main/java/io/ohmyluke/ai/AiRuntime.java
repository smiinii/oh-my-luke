package io.ohmyluke.ai;

/** Provider-independent boundary for one AI invocation. */
public interface AiRuntime {
    /** Stable runtime configuration identity used by persisted graph signatures. */
    String fingerprint();

    /** Returns a structured success or failure and never exposes authentication material. */
    AiRuntimeResult invoke(AiRequest request);
}
