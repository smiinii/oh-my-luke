package io.ohmyluke.runtime;

import io.ohmyluke.graph.Condition;
import io.ohmyluke.graph.Edge;
import io.ohmyluke.graph.GraphDefinition;
import io.ohmyluke.graph.GraphRunner;
import io.ohmyluke.graph.GraphValidator;
import io.ohmyluke.graph.Node;
import io.ohmyluke.graph.NodeContext;
import io.ohmyluke.graph.NodeId;
import io.ohmyluke.graph.NodeResult;
import io.ohmyluke.graph.RunState;
import io.ohmyluke.graph.RunStatus;
import io.ohmyluke.graph.StatePatch;
import io.ohmyluke.state.CheckpointCodec;
import io.ohmyluke.state.CheckpointStore;
import io.ohmyluke.state.EventLogStore;
import io.ohmyluke.state.HandoffNote;
import io.ohmyluke.state.HandoffStore;
import io.ohmyluke.state.RunEventCodec;
import io.ohmyluke.state.RunLockManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/** Child-process fixture used to prove recovery after Runtime.halt. */
public final class ForcedTerminationFixture {
    private static final String RUN_ID = "forced-run";

    private ForcedTerminationFixture() {}

    public static void main(String[] args) {
        Path projectRoot = Path.of(args[0]);
        String mode = args[1];
        GraphDefinition graph = graph(projectRoot.resolve("node-started.marker"));
        ManagedRunService service = service(projectRoot);
        if ("crash".equals(mode)) {
            service.start(RUN_ID, graph, handoff());
        }
        RunState result = service.resume(RUN_ID, graph);
        if (result.status() != RunStatus.COMPLETED) {
            throw new IllegalStateException("run did not complete: " + result.status());
        }
    }

    private static ManagedRunService service(Path projectRoot) {
        return new ManagedRunService(
                new GraphRunner(new GraphValidator()),
                new CheckpointStore(projectRoot, new CheckpointCodec()),
                new EventLogStore(projectRoot, new RunEventCodec()),
                new HandoffStore(projectRoot),
                new RunLockManager(projectRoot));
    }

    private static GraphDefinition graph(Path marker) {
        NodeId work = new NodeId("work");
        NodeId end = new NodeId("end");
        Node node = new CrashOnceNode(work, marker);
        return new GraphDefinition(
                work,
                Set.of(node),
                List.of(new Edge(work, end, Condition.always())),
                Set.of(end),
                0);
    }

    private static HandoffNote handoff() {
        return new HandoffNote(
                "강제 종료 후 재개",
                List.of("그래프가 검증됨"),
                List.of(),
                List.of(),
                List.of("체크포인트를 건너뛰지 않는다"),
                "중단된 노드를 다시 실행한다");
    }

    private record CrashOnceNode(NodeId id, Path marker) implements Node {
        @Override
        public String fingerprint() {
            return "forced-termination-node-v1";
        }

        @Override
        public NodeResult execute(NodeContext context) throws Exception {
            if (Files.notExists(marker)) {
                Files.writeString(marker, "started");
                Runtime.getRuntime().halt(23);
            }
            return NodeResult.success(StatePatch.of("result", "done"));
        }
    }
}
