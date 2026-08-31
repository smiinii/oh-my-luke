package io.ohmyluke.runtime;

import static org.junit.jupiter.api.Assertions.*;

import io.ohmyluke.graph.*;
import io.ohmyluke.policy.PolicyConfiguration;
import io.ohmyluke.state.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManagedApprovalTest {
    private static final NodeId GATE = new NodeId("review");
    private static final NodeId WORK = new NodeId("work");
    private static final NodeId END = new NodeId("end");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-31T00:00:00Z"), ZoneOffset.UTC);
    @TempDir Path project;

    @Test
    void waitsDurablyWithoutCallsAndDecisionDoesNotExecuteTheNextNode() {
        AtomicInteger calls = new AtomicInteger();
        GraphDefinition graph = graph(calls, false);
        ManagedRunService first = service();
        first.start("approval", graph, handoff());
        RunState waiting = first.resume("approval", graph);
        ApprovalState request = first.inspect("approval").approval();
        assertNotNull(request);
        assertEquals(ApprovalDecision.PENDING, request.decision());
        assertEquals(waiting, first.step("approval", graph));
        assertEquals(waiting, service().resume("approval", graph));
        assertEquals(0, calls.get());
        assertEquals(0, first.inspect("approval").policyState().nodeCalls());

        ManagedRunService restarted = service();
        restarted.decideApproval("approval", graph, request.requestId(), true);
        assertEquals(0, calls.get());
        assertEquals(ApprovalDecision.APPROVED, restarted.inspect("approval").approval().decision());
        assertEquals(RunStatus.COMPLETED, service().resume("approval", graph).status());
        assertEquals(1, calls.get());
        assertNull(service().inspect("approval").approval());
    }

    @Test
    void deniedDecisionFollowsFailureEdgeWithoutExecutingProtectedWork() {
        AtomicInteger calls = new AtomicInteger();
        GraphDefinition graph = graph(calls, false);
        ManagedRunService runs = service();
        runs.start("denied", graph, handoff());
        runs.decideApproval("denied", graph, runs.inspect("denied").approval().requestId(), false);
        assertEquals(RunStatus.COMPLETED, runs.resume("denied", graph).status());
        assertEquals(0, calls.get());
        assertEquals(Outcome.FAILURE, runs.inspect("denied").state().events().getFirst().outcome());
    }

    @Test
    void rejectsStaleDuplicateAndCrossRunDecisionsAndRequiresANewApprovalOnRevisit() {
        AtomicInteger calls = new AtomicInteger();
        GraphDefinition graph = graph(calls, true);
        ManagedRunService runs = service();
        runs.start("first", graph, handoff());
        runs.start("other", graph, handoff());
        String first = runs.inspect("first").approval().requestId();
        assertThrows(ManagedRunException.class, () -> runs.decideApproval("other", graph, first, true));
        runs.decideApproval("first", graph, first, true);
        assertThrows(ManagedRunException.class, () -> runs.decideApproval("first", graph, first, true));
        assertThrows(ManagedRunException.class, () -> runs.decideApproval("first", graph, first, false));
        runs.resume("first", graph);
        assertEquals(1, calls.get());
        String second = runs.inspect("first").approval().requestId();
        assertNotEquals(first, second);
        assertThrows(ManagedRunException.class, () -> runs.decideApproval("first", graph, first, true));
    }

    @Test
    void cancellationAndElapsedLimitsCannotBeOverriddenByApproval() {
        GraphDefinition graph = graph(new AtomicInteger(), false);
        ManagedRunService runs = service();
        runs.start("cancelled", graph, handoff());
        String cancelledId = runs.inspect("cancelled").approval().requestId();
        runs.cancel("cancelled");
        assertThrows(ManagedRunException.class, () -> runs.decideApproval("cancelled", graph, cancelledId, true));
        ManagedRunService limited = service(new PolicyConfiguration(0, 1000, 0, 0, 0, 0, 0), CLOCK);
        limited.start("expired", graph, handoff());
        String expiredId = limited.inspect("expired").approval().requestId();
        ManagedRunService later = service(PolicyConfiguration.unlimited(), Clock.offset(CLOCK, Duration.ofSeconds(2)));
        assertThrows(ManagedRunException.class, () -> later.decideApproval("expired", graph, expiredId, true));
        assertEquals(0, later.inspect("expired").state().executedSteps());
    }

    @Test
    void restoresRecordedDecisionAfterPrimaryCorruptionWithoutAskingAgain() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        GraphDefinition graph = graph(calls, false);
        ManagedRunService runs = service();
        runs.start("restore", graph, handoff());
        runs.decideApproval("restore", graph, runs.inspect("restore").approval().requestId(), false);
        Files.writeString(project.resolve(".oml/runs/restore/state.json"), "broken");
        assertEquals(ApprovalDecision.DENIED, service().inspect("restore").approval().decision());
        assertEquals(RunStatus.COMPLETED, service().resume("restore", graph).status());
        assertEquals(0, calls.get());
    }

    @Test
    void restoresAPendingGateFromSafeBackupAndCancellationWinsOverApprovedHistory() throws Exception {
        GraphDefinition graph = graph(new AtomicInteger(), false);
        ManagedRunService runs = service();
        runs.start("pending", graph, handoff());
        ApprovalState pending = runs.inspect("pending").approval();
        Files.writeString(project.resolve(".oml/runs/pending/state.json"), "broken");
        assertEquals(pending, service().inspect("pending").approval());
        assertEquals(0, service().resume("pending", graph).executedSteps());
        runs.decideApproval("pending", graph, pending.requestId(), true);
        runs.cancel("pending");
        Files.writeString(project.resolve(".oml/runs/pending/state.json"), "broken");
        assertEquals(RunStatus.CANCELLED, service().resume("pending", graph).status());
        assertNull(service().inspect("pending").approval());
    }

    @Test
    void recoversDecisionEventWrittenBeforeDecisionCheckpoint() {
        GraphDefinition graph = graph(new AtomicInteger(), false);
        ManagedRunService runs = service();
        runs.start("event-first", graph, handoff());
        CheckpointStore checkpoints = new CheckpointStore(project, new CheckpointCodec());
        RunCheckpoint pending = checkpoints.load("event-first").checkpoint();
        runs.decideApproval("event-first", graph, pending.approval().requestId(), true);
        // Simulate termination after the decision event, before replacing the pending checkpoint.
        checkpoints.save(pending);
        assertEquals(ApprovalDecision.APPROVED, service().inspect("event-first").approval().decision());
        assertEquals(RunStatus.COMPLETED, service().resume("event-first", graph).status());
    }

    @Test
    void recoversApprovalEventsMissingAfterCheckpointSaveWithoutDuplicateRecords() throws Exception {
        GraphDefinition graph = graph(new AtomicInteger(), false);
        ManagedRunService runs = service();
        EventLogStore events = new EventLogStore(project, new RunEventCodec());
        runs.start("checkpoint-first", graph, handoff());
        RunEvent started = events.readAll("checkpoint-first").events().getFirst();
        Files.writeString(events.eventsPath("checkpoint-first"), new RunEventCodec().encode(started) + "\n");
        assertEquals(1, runs.inspect("checkpoint-first").events().stream()
                .filter(event -> event.type() == RunEventType.APPROVAL_REQUESTED).count());
        runs.resume("checkpoint-first", graph);
        runs.resume("checkpoint-first", graph);
        assertEquals(1, events.readAll("checkpoint-first").events().stream()
                .filter(event -> event.type() == RunEventType.APPROVAL_REQUESTED).count());
        String undecidedLog = Files.readString(events.eventsPath("checkpoint-first"));
        runs.decideApproval("checkpoint-first", graph, runs.inspect("checkpoint-first").approval().requestId(), true);
        Files.writeString(events.eventsPath("checkpoint-first"), undecidedLog);
        assertEquals(1, runs.inspect("checkpoint-first").events().stream()
                .filter(event -> event.type() == RunEventType.APPROVAL_DECIDED).count());
        runs.resume("checkpoint-first", graph);
        assertEquals(1, events.readAll("checkpoint-first").events().stream()
                .filter(event -> event.type() == RunEventType.APPROVAL_DECIDED).count());
    }

    @Test
    void retainsConsentWhenInterruptedBeforeApprovalTransitionAndDoesNotRepeatProtectedWork() {
        AtomicInteger calls = new AtomicInteger();
        GraphDefinition graph = graph(calls, false);
        ManagedRunService runs = service();
        runs.start("transition-crash", graph, handoff());
        runs.decideApproval("transition-crash", graph, runs.inspect("transition-crash").approval().requestId(), true);
        CheckpointStore store = new CheckpointStore(project, new CheckpointCodec());
        RunCheckpoint decided = store.load("transition-crash").checkpoint();
        store.save(RunCheckpoint.current(decided.runId(), decided.graphSignature(), CheckpointPhase.NODE_STARTED,
                decided.state(), decided.policyConfiguration(), decided.policyState().withCounters(1, 1, 0, 0), decided.approval()));
        assertEquals(RunStatus.COMPLETED, service().resume("transition-crash", graph).status());
        assertEquals(1, calls.get());
        assertEquals(3, service().inspect("transition-crash").policyState().nodeCalls());
    }

    @Test
    void refusesConflictingDecisionHistory() {
        GraphDefinition graph = graph(new AtomicInteger(), false);
        ManagedRunService runs = service();
        runs.start("conflicting", graph, handoff());
        runs.decideApproval("conflicting", graph, runs.inspect("conflicting").approval().requestId(), true);
        RunInspection inspection = runs.inspect("conflicting");
        new EventLogStore(project, new RunEventCodec()).append(RunEvent.current("conflicting",
                inspection.events().getLast().sequence() + 1, RunEventType.APPROVAL_DECIDED, GATE,
                RunStatus.RUNNING, 0, ApprovalSupport.encode(inspection.approval().withDecision(ApprovalDecision.DENIED))));
        assertThrows(ManagedRunException.class, () -> runs.resume("conflicting", graph));
    }

    @Test
    void refusesApprovalWhenGraphOrPersistedInputsChanged() {
        GraphDefinition graph = graph(new AtomicInteger(), false);
        ManagedRunService runs = service();
        runs.start("changed", graph, Map.of("proposalHash", "original"), handoff());
        String request = runs.inspect("changed").approval().requestId();
        GraphDefinition changed = new GraphDefinition(graph.start(), graph.nodes(), graph.edges(), graph.terminalNodes(), 11);
        assertThrows(ManagedRunException.class, () -> runs.decideApproval("changed", changed, request, true));
        CheckpointStore store = new CheckpointStore(project, new CheckpointCodec());
        RunCheckpoint before = store.load("changed").checkpoint();
        RunState modified = new RunState(before.state().status(), before.state().currentNode(), 0,
                Map.of("proposalHash", "replaced"), before.state().path(), before.state().events());
        store.save(RunCheckpoint.current(before.runId(), before.graphSignature(), before.phase(), modified,
                before.policyConfiguration(), before.policyState(), before.approval()));
        assertThrows(ManagedRunException.class, () -> runs.decideApproval("changed", graph, request, true));
    }

    @Test
    void refusesConcurrentDecisionWhileTheRunLeaseIsHeld() {
        GraphDefinition graph = graph(new AtomicInteger(), false);
        ManagedRunService runs = service();
        runs.start("locked", graph, handoff());
        String request = runs.inspect("locked").approval().requestId();
        try (RunLockManager.RunLease ignored = new RunLockManager(project).acquire("locked")) {
            assertThrows(CheckpointException.class, () -> service().decideApproval("locked", graph, request, true));
            assertThrows(CheckpointException.class, () -> service().resume("locked", graph));
        }
        assertEquals(ApprovalDecision.PENDING, runs.inspect("locked").approval().decision());
    }

    @Test
    void resumesTheSameApprovalAfterTheWaitingJvmWasForciblyTerminated() throws Exception {
        Process first = fixture("wait");
        assertTrue(first.waitFor(20, TimeUnit.SECONDS));
        assertEquals(23, first.exitValue(), new String(first.getInputStream().readAllBytes()));
        ApprovalState pending = service().inspect("process-approval").approval();
        assertEquals(ApprovalDecision.PENDING, pending.decision());
        Process restarted = fixture("approve");
        assertTrue(restarted.waitFor(20, TimeUnit.SECONDS));
        assertEquals(0, restarted.exitValue(), new String(restarted.getInputStream().readAllBytes()));
        assertEquals(RunStatus.COMPLETED, service().inspect("process-approval").state().status());
        assertTrue(service().inspect("process-approval").events().stream()
                .anyMatch(event -> event.type() == RunEventType.APPROVAL_DECIDED
                        && event.detail().contains(pending.requestId())));
    }

    private Process fixture(String mode) throws Exception {
        return new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), ApprovalRestartFixture.class.getName(),
                project.toString(), mode).redirectErrorStream(true).start();
    }

    @Test
    void pureRunnerCannotExecuteAnApprovalNodeAndResolutionRejectsOrdinaryNodes() {
        GraphRunner runner = new GraphRunner(new GraphValidator());
        GraphDefinition graph = graph(new AtomicInteger(), false);
        RunState initial = runner.start(graph);
        assertThrows(GraphExecutionException.class, () -> runner.step(graph, initial));
        RunState resolved = runner.resolveApproval(runner.prepare(graph), initial, "pure", true);
        assertEquals(WORK, resolved.currentNode());
        assertThrows(GraphExecutionException.class,
                () -> runner.resolveApproval(runner.prepare(graph), resolved, "pure", true));
    }

    private ManagedRunService service() { return service(PolicyConfiguration.unlimited(), CLOCK); }
    private ManagedRunService service(PolicyConfiguration policy, Clock clock) {
        return new ManagedRunService(new GraphRunner(new GraphValidator()),
                new CheckpointStore(project, new CheckpointCodec()), new EventLogStore(project, new RunEventCodec()),
                new HandoffStore(project), new RunLockManager(project), policy, clock);
    }
    private static HandoffNote handoff() {
        return new HandoffNote("Approval test", List.of(), List.of(), List.of(), List.of(), "resume");
    }
    private static GraphDefinition graph(AtomicInteger calls, boolean loop) {
        Node work = new Node() {
            @Override public NodeId id() { return WORK; }
            @Override public String fingerprint() { return "approval-test-work-v1"; }
            @Override public NodeResult execute(NodeContext context) {
                calls.incrementAndGet();
                return NodeResult.success(StatePatch.of("work", "done"));
            }
        };
        return new GraphDefinition(GATE, Set.of(new ApprovalNode(GATE, "Continue with the reviewed change?"), work),
                List.of(new Edge(GATE, WORK, Condition.outcomeIs(Outcome.SUCCESS)),
                        new Edge(GATE, END, Condition.outcomeIs(Outcome.FAILURE)),
                        new Edge(GATE, END, Condition.outcomeIs(Outcome.SKIPPED)),
                        new Edge(GATE, END, Condition.outcomeIs(Outcome.CANCELLED)),
                        new Edge(WORK, loop ? GATE : END, Condition.always())), Set.of(END), 10);
    }
}
