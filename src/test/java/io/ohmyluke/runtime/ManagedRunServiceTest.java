package io.ohmyluke.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ohmyluke.graph.Condition;
import io.ohmyluke.graph.Edge;
import io.ohmyluke.graph.GraphDefinition;
import io.ohmyluke.graph.GraphExecutionException;
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
import io.ohmyluke.state.CheckpointException;
import io.ohmyluke.state.CheckpointPhase;
import io.ohmyluke.state.CheckpointStore;
import io.ohmyluke.state.EventLogStore;
import io.ohmyluke.state.HandoffNote;
import io.ohmyluke.state.HandoffStore;
import io.ohmyluke.state.RunEventCodec;
import io.ohmyluke.state.RunEventType;
import io.ohmyluke.state.RunLockManager;
import io.ohmyluke.state.RunCheckpoint;
import io.ohmyluke.state.GraphSignature;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManagedRunServiceTest {
    private static final NodeId WORK = new NodeId("work");
    private static final NodeId END = new NodeId("end");

    @TempDir
    Path projectRoot;

    @Test
    void writesTheCompleteRunDirectoryAndLifecycleEvents() {
        ManagedRunService service = service();
        GraphDefinition graph = oneNodeGraph(context -> NodeResult.success(
                StatePatch.of("result", "done")), 0);

        service.start("run-001", graph, Map.of("request", "same"), handoff());
        RunState result = service.resume("run-001", graph);
        RunInspection inspection = service.inspect("run-001");

        assertEquals(RunStatus.COMPLETED, result.status());
        assertEquals(Map.of("request", "same", "result", "done"), result.values());
        assertEquals(CheckpointPhase.READY, inspection.phase());
        assertEquals(
                List.of(
                        RunEventType.RUN_STARTED,
                        RunEventType.RUN_RESUMED,
                        RunEventType.NODE_STARTED,
                        RunEventType.NODE_COMPLETED,
                        RunEventType.RUN_COMPLETED),
                inspection.events().stream().map(event -> event.type()).toList());
        assertEquals(
                WORK,
                inspection.events().stream()
                        .filter(event -> event.type() == RunEventType.NODE_COMPLETED)
                        .findFirst()
                        .orElseThrow()
                        .node());
        assertTrue(Files.exists(projectRoot.resolve(".oml/runs/run-001/state.json")));
        assertTrue(Files.exists(projectRoot.resolve(".oml/runs/run-001/events.jsonl")));
        assertTrue(Files.exists(projectRoot.resolve(".oml/runs/run-001/handoff.md")));
        assertFalse(inspection.recoveredFromBackup());
    }

    @Test
    void resumesTheInterruptedNodeFromTheLastSafeState() {
        AtomicInteger attempts = new AtomicInteger();
        GraphDefinition crashOnce = oneNodeGraph(context -> {
            if (attempts.incrementAndGet() == 1) {
                throw new IllegalStateException("simulated process interruption");
            }
            return NodeResult.success(StatePatch.of("result", "done"));
        }, 0);
        GraphDefinition uninterrupted = oneNodeGraph(context -> NodeResult.success(
                StatePatch.of("result", "done")), 0);
        ManagedRunService service = service();
        service.start("run-001", crashOnce, Map.of("request", "same"), handoff());

        assertThrows(GraphExecutionException.class, () -> service.resume("run-001", crashOnce));
        RunInspection interrupted = service.inspect("run-001");
        assertEquals(CheckpointPhase.NODE_STARTED, interrupted.phase());
        assertEquals(0, interrupted.state().executedSteps());

        RunState resumed = service.resume("run-001", crashOnce);
        RunState expected = new GraphRunner(new GraphValidator()).run(
                uninterrupted,
                Map.of("request", "same"));

        assertEquals(expected, resumed);
        assertEquals(2, attempts.get());
    }

    @Test
    void resumesAfterTheExecutingJvmIsForciblyTerminated() throws Exception {
        Process crashed = fixtureProcess("crash");
        int crashExit = crashed.waitFor();
        String crashOutput = new String(crashed.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertEquals(23, crashExit, crashOutput);
        assertEquals(CheckpointPhase.NODE_STARTED, service().inspect("forced-run").phase());

        Process resumed = fixtureProcess("resume");
        int resumeExit = resumed.waitFor();
        String resumeOutput = new String(resumed.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertEquals(0, resumeExit, resumeOutput);
        RunInspection completed = service().inspect("forced-run");
        assertEquals(RunStatus.COMPLETED, completed.state().status());
        assertEquals(1, completed.state().executedSteps());
        assertEquals("done", completed.state().values().get("result"));
    }

    @Test
    void rejectsResumeWhenTheGraphChanged() {
        ManagedRunService service = service();
        GraphDefinition original = oneNodeGraph(context -> NodeResult.success(), 0);
        GraphDefinition changed = oneNodeGraph(context -> NodeResult.success(), 2);
        service.start("run-001", original, handoff());

        assertThrows(ManagedRunException.class, () -> service.resume("run-001", changed));
        assertEquals(0, service.inspect("run-001").state().executedSteps());
    }

    @Test
    void rejectsStartingAnExistingRunId() {
        ManagedRunService service = service();
        GraphDefinition graph = oneNodeGraph(context -> NodeResult.success(), 0);
        service.start("run-001", graph, handoff());

        assertThrows(ManagedRunException.class, () -> service.start("run-001", graph, handoff()));
    }

    @Test
    void cancelledRunCannotExecuteWhenResumed() {
        AtomicInteger executions = new AtomicInteger();
        GraphDefinition graph = oneNodeGraph(context -> {
            executions.incrementAndGet();
            return NodeResult.success();
        }, 0);
        ManagedRunService service = service();
        service.start("run-001", graph, handoff());

        RunState cancelled = service.cancel("run-001");
        RunState resumed = service.resume("run-001", graph);

        assertEquals(RunStatus.CANCELLED, cancelled.status());
        assertEquals(cancelled, resumed);
        assertEquals(0, executions.get());
        assertEquals(RunEventType.RUN_CANCELLED, service.inspect("run-001").events().getLast().type());
    }

    @Test
    void cancelledRunStaysCancelledWhenThePrimaryCheckpointIsCorrupt() throws Exception {
        AtomicInteger executions = new AtomicInteger();
        GraphDefinition graph = oneNodeGraph(context -> {
            executions.incrementAndGet();
            return NodeResult.success();
        }, 0);
        CheckpointStore checkpoints = new CheckpointStore(projectRoot, new CheckpointCodec());
        ManagedRunService service = service(checkpoints, new EventLogStore(projectRoot, new RunEventCodec()));
        service.start("run-001", graph, handoff());
        service.cancel("run-001");
        Files.writeString(checkpoints.statePath("run-001"), "{broken-json");

        RunState resumed = service.resume("run-001", graph);

        assertEquals(RunStatus.CANCELLED, resumed.status());
        assertEquals(0, executions.get());
        assertEquals(RunStatus.CANCELLED, checkpoints.load("run-001").checkpoint().state().status());
    }

    @Test
    void onlyOneServiceCanResumeTheSameRunAtATime() throws Exception {
        CountDownLatch enteredNode = new CountDownLatch(1);
        CountDownLatch releaseNode = new CountDownLatch(1);
        AtomicInteger executions = new AtomicInteger();
        GraphDefinition graph = oneNodeGraph(context -> {
            executions.incrementAndGet();
            enteredNode.countDown();
            try {
                if (!releaseNode.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("test did not release node");
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(error);
            }
            return NodeResult.success();
        }, 0);
        ManagedRunService first = service();
        ManagedRunService second = service();
        first.start("run-001", graph, handoff());
        CompletableFuture<RunState> active = CompletableFuture.supplyAsync(
                () -> first.resume("run-001", graph));
        assertTrue(enteredNode.await(5, TimeUnit.SECONDS));

        try {
            assertThrows(CheckpointException.class, () -> second.resume("run-001", graph));
        } finally {
            releaseNode.countDown();
        }

        assertEquals(RunStatus.COMPLETED, active.get(5, TimeUnit.SECONDS).status());
        assertEquals(1, executions.get());
    }

    @Test
    void resumeReconstructsCompletionEventsWhenCheckpointWasSavedFirst() {
        GraphDefinition graph = oneNodeGraph(context -> NodeResult.success(), 0);
        GraphRunner runner = new GraphRunner(new GraphValidator());
        CheckpointStore checkpoints = new CheckpointStore(projectRoot, new CheckpointCodec());
        EventLogStore events = new EventLogStore(projectRoot, new RunEventCodec());
        ManagedRunService service = service(checkpoints, events);
        service.start("run-001", graph, handoff());
        RunState completed = runner.step(graph, checkpoints.load("run-001").checkpoint().state());
        checkpoints.save(RunCheckpoint.current(
                "run-001",
                GraphSignature.calculate(graph),
                CheckpointPhase.READY,
                completed));

        List<RunEventType> inspectionTypes = service.inspect("run-001").events().stream()
                .map(event -> event.type())
                .toList();
        assertTrue(inspectionTypes.contains(RunEventType.NODE_COMPLETED));
        assertTrue(inspectionTypes.contains(RunEventType.RUN_COMPLETED));
        assertFalse(events.readAll("run-001").events().stream()
                .anyMatch(event -> event.type() == RunEventType.RUN_COMPLETED));

        RunState resumed = service.resume("run-001", graph);
        List<RunEventType> eventTypes = events.readAll("run-001").events().stream()
                .map(event -> event.type())
                .toList();

        assertEquals(RunStatus.COMPLETED, resumed.status());
        assertTrue(eventTypes.contains(RunEventType.NODE_COMPLETED));
        assertTrue(eventTypes.contains(RunEventType.RUN_COMPLETED));
    }

    private ManagedRunService service() {
        return service(
                new CheckpointStore(projectRoot, new CheckpointCodec()),
                new EventLogStore(projectRoot, new RunEventCodec()));
    }

    private ManagedRunService service(CheckpointStore checkpoints, EventLogStore events) {
        return new ManagedRunService(
                new GraphRunner(new GraphValidator()),
                checkpoints,
                events,
                new HandoffStore(projectRoot),
                new RunLockManager(projectRoot));
    }

    private Process fixtureProcess(String mode) throws Exception {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        return new ProcessBuilder(
                        java.toString(),
                        "-cp",
                        System.getProperty("java.class.path"),
                        ForcedTerminationFixture.class.getName(),
                        projectRoot.toString(),
                        mode)
                .redirectErrorStream(true)
                .start();
    }

    private static GraphDefinition oneNodeGraph(
            Function<NodeContext, NodeResult> action,
            int maxSteps) {
        Node node = new TestNode(WORK, action);
        return new GraphDefinition(
                WORK,
                Set.of(node),
                List.of(new Edge(WORK, END, Condition.always())),
                Set.of(END),
                maxSteps);
    }

    private static HandoffNote handoff() {
        return new HandoffNote(
                "테스트 실행 완료",
                List.of("그래프가 검증됨"),
                List.of(),
                List.of(),
                List.of("검증을 건너뛰지 않는다"),
                "현재 노드를 실행한다");
    }

    private record TestNode(NodeId id, Function<NodeContext, NodeResult> action) implements Node {
        @Override
        public String fingerprint() {
            return "managed-run-test-node-v1";
        }

        @Override
        public NodeResult execute(NodeContext context) {
            return action.apply(context);
        }
    }
}
