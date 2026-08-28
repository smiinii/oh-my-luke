package io.ohmyluke.state;

import io.ohmyluke.graph.Edge;
import io.ohmyluke.graph.GraphDefinition;
import io.ohmyluke.graph.Node;
import io.ohmyluke.graph.NodeId;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** Computes a stable fingerprint of the serializable graph structure. */
public final class GraphSignature {
    private GraphSignature() {}

    public static String calculate(GraphDefinition graph) {
        Objects.requireNonNull(graph, "graph");
        StringBuilder canonical = new StringBuilder();
        append(canonical, "start", graph.start().value());
        append(canonical, "maxSteps", Integer.toString(graph.maxSteps()));
        graph.nodes().stream()
                .sorted(Comparator.comparing(node -> node.id().value()))
                .forEach(node -> {
                    append(canonical, "node.id", node.id().value());
                    append(canonical, "node.fingerprint", node.fingerprint());
                });
        graph.terminalNodes().stream()
                .map(NodeId::value)
                .sorted()
                .forEach(value -> append(canonical, "terminal", value));
        List<Edge> edges = graph.edges().stream()
                .sorted(Comparator.comparing((Edge edge) -> edge.from().value())
                        .thenComparing(edge -> edge.to().value())
                        .thenComparing(edge -> edge.condition().description()))
                .toList();
        for (Edge edge : edges) {
            append(canonical, "edge.from", edge.from().value());
            append(canonical, "edge.to", edge.to().value());
            append(canonical, "edge.condition", edge.condition().description());
        }
        return sha256(canonical.toString());
    }

    private static void append(StringBuilder target, String name, String value) {
        target.append(name.length()).append(':').append(name)
                .append(value.length()).append(':').append(value);
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }
}
