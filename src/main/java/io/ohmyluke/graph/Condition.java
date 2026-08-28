package io.ohmyluke.graph;

import java.util.Map;
import java.util.Objects;

/** Deterministic predicate that decides whether an edge can be followed. */
public sealed interface Condition permits Condition.Always, Condition.OutcomeIs {
    boolean matches(NodeResult result, Map<String, String> stateAfterNode);

    String description();

    static Condition always() {
        return new Always();
    }

    static Condition outcomeIs(Outcome expected) {
        return new OutcomeIs(expected);
    }

    record Always() implements Condition {
        @Override
        public boolean matches(NodeResult result, Map<String, String> stateAfterNode) {
            Objects.requireNonNull(result, "result");
            Objects.requireNonNull(stateAfterNode, "stateAfterNode");
            return true;
        }

        @Override
        public String description() {
            return "always";
        }
    }

    record OutcomeIs(Outcome expected) implements Condition {
        public OutcomeIs {
            Objects.requireNonNull(expected, "expected");
        }

        @Override
        public boolean matches(NodeResult result, Map<String, String> stateAfterNode) {
            Objects.requireNonNull(result, "result");
            Objects.requireNonNull(stateAfterNode, "stateAfterNode");
            return result.outcome() == expected;
        }

        @Override
        public String description() {
            return "outcome == " + expected;
        }
    }
}
