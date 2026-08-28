package io.ohmyluke.cli;

import io.ohmyluke.graph.GraphDefinition;
import java.util.Optional;

/** Resolves executable graph code for a persisted structural graph signature. */
@FunctionalInterface
public interface GraphResolver {
    Optional<GraphDefinition> resolve(String graphSignature);

    static GraphResolver none() {
        return ignored -> Optional.empty();
    }
}
