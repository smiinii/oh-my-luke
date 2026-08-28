package io.ohmyluke.graph;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Creates immutable string maps without discarding their iteration order. */
final class ImmutableStringMap {
    private ImmutableStringMap() {}

    static Map<String, String> copyOf(Map<String, String> values) {
        Objects.requireNonNull(values, "values");
        LinkedHashMap<String, String> copy = new LinkedHashMap<>();
        values.forEach((key, value) -> copy.put(
                Objects.requireNonNull(key, "state key"),
                Objects.requireNonNull(value, "state value")));
        return Collections.unmodifiableMap(copy);
    }
}
