package io.ohmyluke.graph;

import java.util.Objects;

/** Stable identifier used to connect nodes and edges. */
public record NodeId(String value) {
    public NodeId {
        value = Objects.requireNonNull(value, "value").trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("node id must not be blank");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
