package io.ohmyluke.tool;

import io.ohmyluke.graph.Node;
import io.ohmyluke.graph.NodeContext;
import io.ohmyluke.graph.NodeId;
import io.ohmyluke.graph.NodeResult;
import io.ohmyluke.policy.ToolPermission;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Objects;

/** Graph node adapter for one immutable, sandboxed process request. */
public final class ProcessToolNode implements Node {
    private final NodeId id;
    private final ProcessTool tool;
    private final ProcessToolRequest request;
    private final ToolArtifactStore artifacts;
    private final String fingerprint;

    public ProcessToolNode(
            NodeId id,
            ProcessTool tool,
            ProcessToolRequest request,
            ToolArtifactStore artifacts) {
        this.id = Objects.requireNonNull(id, "id");
        this.tool = Objects.requireNonNull(tool, "tool");
        this.request = Objects.requireNonNull(request, "request");
        this.artifacts = Objects.requireNonNull(artifacts, "artifacts");
        this.fingerprint = "process-tool:v1:" + ToolNodeSupport.fingerprint(
                request.operationId()
                        + "\0" + request.executable()
                        + "\0" + request.arguments()
                        + "\0" + request.workingDirectory()
                        + "\0" + new java.util.TreeMap<>(request.environment())
                        + "\0" + request.timeout()
                        + "\0" + request.maxOutputBytes()
                        + "\0" + request.capability()
                        + "\0" + request.permissionTarget());
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
        ProcessToolResult result = tool.execute(request);
        LinkedHashMap<String, String> details = new LinkedHashMap<>();
        details.put(key("executed"), Boolean.toString(result.executed()));
        details.put(key("exitCode"), Integer.toString(result.exitCode()));
        details.put(key("timedOut"), Boolean.toString(result.timedOut()));
        details.put(key("outputTruncated"), Boolean.toString(result.outputTruncated()));
        details.put(key("elapsedMillis"), Long.toString(result.elapsedMillis()));
        if (!result.standardOutput().isEmpty()) {
            details.put(key("stdoutArtifact"), artifacts.store(
                    request.operationId(),
                    "stdout.txt",
                    result.standardOutput().getBytes(StandardCharsets.UTF_8)));
        }
        if (!result.standardError().isEmpty()) {
            details.put(key("stderrArtifact"), artifacts.store(
                    request.operationId(),
                    "stderr.txt",
                    result.standardError().getBytes(StandardCharsets.UTF_8)));
        }
        boolean success = result.permission().permission() == ToolPermission.ALLOW
                && result.executed()
                && !result.timedOut()
                && result.exitCode() == 0;
        String failureCode = result.timedOut()
                ? "timeout"
                : result.permission().permission() == ToolPermission.DENY
                        ? "permission-denied"
                        : "nonzero-exit";
        return ToolNodeSupport.result(
                prefix(),
                result.permission(),
                success,
                "process-tool",
                failureCode,
                result.executed() ? result.detail() : result.permission().reasonCode(),
                details);
    }

    private String prefix() {
        return "tool." + id.value();
    }

    private String key(String suffix) {
        return prefix() + "." + suffix;
    }
}
