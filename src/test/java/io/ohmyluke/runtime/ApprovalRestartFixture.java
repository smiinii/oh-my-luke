package io.ohmyluke.runtime;

import io.ohmyluke.graph.*;
import io.ohmyluke.state.*;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/** Separate JVMs prove that approval waiting does not depend on an in-memory callback. */
public final class ApprovalRestartFixture {
    private ApprovalRestartFixture() {}
    public static void main(String[] args) {
        Path project = Path.of(args[0]);
        NodeId gate = new NodeId("gate");
        NodeId end = new NodeId("end");
        GraphDefinition graph = new GraphDefinition(gate, Set.of(new ApprovalNode(gate, "Continue?")),
                List.of(new Edge(gate, end, Condition.always())), Set.of(end), 1);
        ManagedRunService runs = new ManagedRunService(new GraphRunner(new GraphValidator()),
                new CheckpointStore(project, new CheckpointCodec()), new EventLogStore(project, new RunEventCodec()),
                new HandoffStore(project), new RunLockManager(project));
        if (args[1].equals("wait")) {
            runs.start("process-approval", graph,
                    new HandoffNote("restart approval", List.of(), List.of(), List.of(), List.of(), "approve"));
            runs.resume("process-approval", graph);
            Runtime.getRuntime().halt(23);
        }
        String request = runs.inspect("process-approval").approval().requestId();
        runs.decideApproval("process-approval", graph, request, true);
        if (runs.resume("process-approval", graph).status() != RunStatus.COMPLETED) {
            throw new IllegalStateException("approved graph did not complete");
        }
    }
}
