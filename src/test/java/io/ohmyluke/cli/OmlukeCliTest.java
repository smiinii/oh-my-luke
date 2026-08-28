package io.ohmyluke.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ohmyluke.graph.Condition;
import io.ohmyluke.graph.Edge;
import io.ohmyluke.graph.GraphDefinition;
import io.ohmyluke.graph.GraphRunner;
import io.ohmyluke.graph.GraphValidator;
import io.ohmyluke.graph.Node;
import io.ohmyluke.graph.NodeContext;
import io.ohmyluke.graph.NodeId;
import io.ohmyluke.graph.NodeResult;
import io.ohmyluke.runtime.ManagedRunService;
import io.ohmyluke.state.CheckpointCodec;
import io.ohmyluke.state.CheckpointStore;
import io.ohmyluke.state.EventLogStore;
import io.ohmyluke.state.GraphSignature;
import io.ohmyluke.state.HandoffNote;
import io.ohmyluke.state.HandoffStore;
import io.ohmyluke.state.RunEventCodec;
import io.ohmyluke.state.RunLockManager;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OmlukeCliTest {
    @TempDir
    Path projectRoot;

    @Test
    void inspectCancelAndResumeCommandsUseDurableRunState() {
        GraphDefinition graph = graph();
        ManagedRunService runs = service();
        runs.start("inspect-run", graph, handoff());
        runs.start("cancel-run", graph, handoff());
        runs.start("resume-run", graph, handoff());
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        GraphResolver resolver = signature -> GraphSignature.calculate(graph).equals(signature)
                ? Optional.of(graph)
                : Optional.empty();
        OmlukeCli cli = new OmlukeCli(
                runs,
                resolver,
                new PrintStream(output, true, StandardCharsets.UTF_8),
                new PrintStream(errors, true, StandardCharsets.UTF_8));

        assertEquals(0, cli.execute(new String[] {"inspect", "inspect-run"}));
        assertEquals(0, cli.execute(new String[] {"cancel", "cancel-run"}));
        assertEquals(0, cli.execute(new String[] {"resume", "resume-run"}));

        String text = output.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("runId=inspect-run\nstatus=RUNNING"));
        assertTrue(text.contains("policyOutcome=CONTINUE"));
        assertTrue(text.contains("policyReason=policy.not-evaluated"));
        assertTrue(text.contains("iterations=0\nnodeCalls=0\ntoolCalls=0\nusage=0"));
        assertTrue(text.contains("runId=cancel-run\nstatus=CANCELLED"));
        assertTrue(text.contains("runId=resume-run\nstatus=COMPLETED\nexecutedSteps=1"));
        assertEquals("", errors.toString(StandardCharsets.UTF_8));
    }

    private ManagedRunService service() {
        return new ManagedRunService(
                new GraphRunner(new GraphValidator()),
                new CheckpointStore(projectRoot, new CheckpointCodec()),
                new EventLogStore(projectRoot, new RunEventCodec()),
                new HandoffStore(projectRoot),
                new RunLockManager(projectRoot));
    }

    private static GraphDefinition graph() {
        NodeId work = new NodeId("work");
        NodeId end = new NodeId("end");
        Node node = new TestNode(work);
        return new GraphDefinition(
                work,
                Set.of(node),
                List.of(new Edge(work, end, Condition.always())),
                Set.of(end),
                0);
    }

    private static HandoffNote handoff() {
        return new HandoffNote(
                "테스트 완료",
                List.of("그래프가 검증됨"),
                List.of(),
                List.of(),
                List.of("검증을 건너뛰지 않는다"),
                "현재 노드를 실행한다");
    }

    private record TestNode(NodeId id) implements Node {
        @Override
        public String fingerprint() {
            return "cli-test-node-v1";
        }

        @Override
        public NodeResult execute(NodeContext context) {
            return NodeResult.success();
        }
    }
}
