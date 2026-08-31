package io.ohmyluke.runtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.ohmyluke.graph.ApprovalNode;
import io.ohmyluke.graph.GraphDefinition;
import io.ohmyluke.graph.RunStatus;
import io.ohmyluke.state.ApprovalDecision;
import io.ohmyluke.state.ApprovalState;
import io.ohmyluke.state.RunCheckpoint;
import io.ohmyluke.state.RunEvent;
import io.ohmyluke.state.RunEventType;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/** Pure approval identity and recovery rules shared by action and inspection paths. */
final class ApprovalSupport {
    private static final ObjectMapper JSON = new ObjectMapper();
    private ApprovalSupport() {}

    static ApprovalState pending(RunCheckpoint checkpoint, GraphDefinition graph) {
        if (checkpoint.state().status() != RunStatus.RUNNING) { return null; }
        return graph.nodes().stream()
                .filter(node -> node.id().equals(checkpoint.state().currentNode()))
                .filter(ApprovalNode.class::isInstance)
                .map(ApprovalNode.class::cast)
                .map(node -> new ApprovalState(requestId(checkpoint), node.id(), node.prompt(), ApprovalDecision.PENDING))
                .findFirst().orElse(null);
    }

    static String requestId(RunCheckpoint checkpoint) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String field : List.of("approval:v1", checkpoint.runId(), checkpoint.graphSignature(),
                    checkpoint.state().currentNode().value(), Integer.toString(checkpoint.state().executedSteps()),
                    RunStateFingerprint.calculate(checkpoint.state()))) {
                byte[] bytes = field.getBytes(StandardCharsets.UTF_8);
                digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array());
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required", impossible);
        }
    }

    static RunCheckpoint withApproval(RunCheckpoint checkpoint, ApprovalState approval) {
        return RunCheckpoint.current(checkpoint.runId(), checkpoint.graphSignature(), checkpoint.phase(),
                checkpoint.state(), checkpoint.policyConfiguration(), checkpoint.policyState(), approval);
    }

    static RunCheckpoint reconcile(RunCheckpoint checkpoint, List<RunEvent> events) {
        if (checkpoint.state().status() != RunStatus.RUNNING) { return checkpoint; }
        ApprovalState current = checkpoint.approval();
        String expected = requestId(checkpoint);
        if (current != null && !current.requestId().equals(expected)) {
            throw new ManagedRunException("approval does not match its persisted input state");
        }
        for (RunEvent event : events) {
            if ((event.type() != RunEventType.APPROVAL_REQUESTED && event.type() != RunEventType.APPROVAL_DECIDED)
                    || event.executedSteps() != checkpoint.state().executedSteps()
                    || !event.node().equals(checkpoint.state().currentNode())) { continue; }
            ApprovalState recorded = decode(event.detail());
            if (!event.runId().equals(checkpoint.runId()) || !recorded.node().equals(event.node())
                    || !recorded.requestId().equals(expected)) {
                throw new ManagedRunException("approval event does not match its input state");
            }
            boolean request = event.type() == RunEventType.APPROVAL_REQUESTED;
            if (request != (recorded.decision() == ApprovalDecision.PENDING)) {
                throw new ManagedRunException("invalid approval lifecycle event");
            }
            if (current != null && !current.prompt().equals(recorded.prompt())) {
                throw new ManagedRunException("approval prompt changed in durable history");
            }
            if (!request && current != null && current.decision() != ApprovalDecision.PENDING
                    && current.decision() != recorded.decision()) {
                throw new ManagedRunException("conflicting durable approval decisions");
            }
            if (current == null || !request) { current = recorded; }
        }
        return java.util.Objects.equals(checkpoint.approval(), current) ? checkpoint : withApproval(checkpoint, current);
    }

    static String encode(ApprovalState approval) {
        try { return JSON.writeValueAsString(approval); }
        catch (JsonProcessingException error) { throw new ManagedRunException("cannot encode approval event"); }
    }

    static ApprovalState decode(String detail) {
        try {
            ApprovalState decoded = JSON.readValue(detail, ApprovalState.class);
            if (decoded == null) { throw new ManagedRunException("invalid approval event"); }
            return decoded;
        }
        catch (JsonProcessingException | IllegalArgumentException error) {
            throw new ManagedRunException("invalid approval event");
        }
    }

    static boolean recorded(List<RunEvent> events, RunCheckpoint checkpoint, ApprovalDecision decision) {
        RunEventType type = decision == ApprovalDecision.PENDING
                ? RunEventType.APPROVAL_REQUESTED : RunEventType.APPROVAL_DECIDED;
        String expected = encode(checkpoint.approval().withDecision(decision));
        return events.stream().anyMatch(event -> event.type() == type
                && event.executedSteps() == checkpoint.state().executedSteps()
                && event.node().equals(checkpoint.state().currentNode()) && event.detail().equals(expected));
    }
}
