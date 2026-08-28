package io.ohmyluke.graph;

import java.util.Map;

/** State changes requested by a node. The runner is the only component that applies them. */
public record StatePatch(Map<String, String> updates) {
    private static final StatePatch EMPTY = new StatePatch(Map.of());

    public StatePatch {
        updates = ImmutableStringMap.copyOf(updates);
    }

    public static StatePatch empty() {
        return EMPTY;
    }

    public static StatePatch of(String key, String value) {
        return new StatePatch(Map.of(key, value));
    }
}
