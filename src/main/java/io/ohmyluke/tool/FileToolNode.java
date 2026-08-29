package io.ohmyluke.tool;

import io.ohmyluke.graph.Node;
import io.ohmyluke.graph.NodeContext;
import io.ohmyluke.graph.NodeId;
import io.ohmyluke.graph.NodeResult;
import io.ohmyluke.policy.ToolPermission;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Graph node adapter for one immutable structured file request. */
public final class FileToolNode implements Node {
    private final NodeId id;
    private final FileTool tool;
    private final FileToolRequest request;
    private final ToolArtifactStore artifacts;
    private final String fingerprint;

    public FileToolNode(
            NodeId id,
            FileTool tool,
            FileToolRequest request,
            ToolArtifactStore artifacts) {
        this.id = Objects.requireNonNull(id, "id");
        this.tool = Objects.requireNonNull(tool, "tool");
        this.request = Objects.requireNonNull(request, "request");
        this.artifacts = Objects.requireNonNull(artifacts, "artifacts");
        this.fingerprint = "file-tool:v1:" + ToolNodeSupport.fingerprint(
                request.operationId()
                        + "\0" + request.operation()
                        + "\0" + request.path()
                        + "\0" + request.destination()
                        + "\0" + ToolNodeSupport.fingerprint(java.util.HexFormat.of().formatHex(request.content())));
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
        FileToolResult result = tool.execute(request);
        LinkedHashMap<String, String> details = new LinkedHashMap<>();
        details.put(key("executed"), Boolean.toString(result.executed()));
        if (result.checkpointId() != null) {
            details.put(key("checkpoint"), result.checkpointId());
        }
        if (result.executed() && result.content().length > 0) {
            details.put(
                    key("artifact"),
                    artifacts.store(request.operationId(), "content.bin", result.content()));
            details.put(key("contentBytes"), Integer.toString(result.content().length));
        }
        boolean success = result.permission().permission() == ToolPermission.ALLOW && result.executed();
        String failureCode = result.permission().permission() == ToolPermission.DENY
                ? "permission-denied"
                : "file-operation-failed";
        return ToolNodeSupport.result(
                prefix(),
                result.permission(),
                success,
                "file-tool",
                failureCode,
                result.executed() ? result.permission().reasonCode() : result.detail(),
                details);
    }

    private String prefix() {
        return "tool." + id.value();
    }

    private String key(String suffix) {
        return prefix() + "." + suffix;
    }
}
