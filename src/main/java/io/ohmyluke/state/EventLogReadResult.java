package io.ohmyluke.state;

import java.util.List;
import java.util.Objects;

/** Parsed event history and whether a crash-truncated final record was ignored. */
public record EventLogReadResult(List<RunEvent> events, boolean ignoredIncompleteTail) {
    public EventLogReadResult {
        events = List.copyOf(Objects.requireNonNull(events, "events"));
    }
}
