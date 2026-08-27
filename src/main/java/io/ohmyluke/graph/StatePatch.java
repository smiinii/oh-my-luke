package io.ohmyluke.graph;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** State changes requested by a node. The runner is the only component that applies them. */
public record StatePatch(Map<String, String> updates) {
    private static final StatePatch EMPTY = new StatePatch(Map.of());

    public StatePatch {
        Objects.requireNonNull(updates, "updates");
        LinkedHashMap<String, String> copy = new LinkedHashMap<>();
        updates.forEach((key, value) -> copy.put(
                Objects.requireNonNull(key, "state key"),
                Objects.requireNonNull(value, "state value")));
        updates = Map.copyOf(copy);
    }

    public static StatePatch empty() {
        return EMPTY;
    }

    public static StatePatch of(String key, String value) {
        return new StatePatch(Map.of(key, value));
    }
}
