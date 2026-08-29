package io.ohmyluke.ai;

import io.ohmyluke.graph.ExecutionMetrics;
import io.ohmyluke.graph.FailureInfo;
import io.ohmyluke.graph.Node;
import io.ohmyluke.graph.NodeContext;
import io.ohmyluke.graph.NodeId;
import io.ohmyluke.graph.NodeResult;
import io.ohmyluke.graph.StatePatch;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/** Graph adapter that sends only selected state to an AI runtime. */
public final class AiNode implements Node {
    private final NodeId id;
    private final AiRuntime runtime;
    private final String instruction;
    private final List<String> inputStateKeys;
    private final String outputStateKey;
    private final String fingerprint;

    public AiNode(
            NodeId id,
            AiRuntime runtime,
            String instruction,
            List<String> inputStateKeys,
            String outputStateKey) {
        this.id = Objects.requireNonNull(id, "id");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.instruction = requireText(instruction, "instruction");
        this.inputStateKeys = copyDistinctKeys(inputStateKeys);
        this.outputStateKey = requireText(outputStateKey, "outputStateKey");
        String runtimeFingerprint = requireText(runtime.fingerprint(), "runtime fingerprint");
        this.fingerprint = "ai-node:v1:sha256:" + AiFingerprints.node(
                runtimeFingerprint,
                this.instruction,
                this.inputStateKeys,
                this.outputStateKey);
    }

    @Override
    public NodeId id() {
        return id;
    }

    @Override
    public String fingerprint() {
        return fingerprint;
    }

    @Override
    public NodeResult execute(NodeContext context) {
        Objects.requireNonNull(context, "context");
        if (!context.explicitRunScope()) {
            return NodeResult.failure(new FailureInfo(
                    "ai-input",
                    "missing-run-scope",
                    "AI node requires an explicit run scope"));
        }
        LinkedHashMap<String, String> selected = new LinkedHashMap<>();
        for (String key : inputStateKeys) {
            String value = context.values().get(key);
            if (value == null) {
                return NodeResult.failure(new FailureInfo(
                        "ai-input",
                        "missing-state",
                        "required state key is missing: " + key));
            }
            selected.put(key, value);
        }

        AiRequest request = new AiRequest(
                AiInvocationId.forNode(context.runId(), id, context.executedSteps()),
                instruction,
                selected);
        AiRuntimeResult result = Objects.requireNonNull(
                runtime.invoke(request),
                "AI runtime result");
        ExecutionMetrics metrics = new ExecutionMetrics(0, result.usage());
        if (result.status() == AiRuntimeStatus.SUCCESS) {
            return NodeResult.success(StatePatch.of(outputStateKey, result.output()), metrics);
        }
        return NodeResult.failure(
                StatePatch.empty(),
                new FailureInfo(
                        "ai-runtime",
                        result.failure().code().stableCode(),
                        result.failure().publicCause()),
                metrics);
    }

    private static List<String> copyDistinctKeys(List<String> keys) {
        Objects.requireNonNull(keys, "inputStateKeys");
        TreeSet<String> distinct = new TreeSet<>();
        for (String key : keys) {
            String checked = requireText(key, "input state key");
            if (!distinct.add(checked)) {
                throw new IllegalArgumentException("inputStateKeys must not contain duplicates");
            }
        }
        return List.copyOf(distinct);
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
