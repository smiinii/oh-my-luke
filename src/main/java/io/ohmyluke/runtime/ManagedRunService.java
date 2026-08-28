package io.ohmyluke.runtime;

import io.ohmyluke.graph.GraphDefinition;
import io.ohmyluke.graph.GraphRunner;
import io.ohmyluke.graph.NodeId;
import io.ohmyluke.graph.RunState;
import io.ohmyluke.graph.RunStatus;
import io.ohmyluke.graph.TransitionEvent;
import io.ohmyluke.state.CheckpointLoadResult;
import io.ohmyluke.state.CheckpointPhase;
import io.ohmyluke.state.CheckpointStore;
import io.ohmyluke.state.EventLogReadResult;
import io.ohmyluke.state.EventLogStore;
import io.ohmyluke.state.GraphSignature;
import io.ohmyluke.state.HandoffNote;
import io.ohmyluke.state.HandoffStore;
import io.ohmyluke.state.RunCheckpoint;
import io.ohmyluke.state.RunEvent;
import io.ohmyluke.state.RunEventType;
import io.ohmyluke.state.RunLockManager;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.Objects;

/** Coordinates graph execution with durable checkpoints, events, and handoff notes. */
public final class ManagedRunService {
    private final GraphRunner runner;
    private final CheckpointStore checkpoints;
    private final EventLogStore eventLog;
    private final HandoffStore handoffs;
    private final RunLockManager locks;

    public ManagedRunService(
            GraphRunner runner,
            CheckpointStore checkpoints,
            EventLogStore eventLog,
            HandoffStore handoffs,
            RunLockManager locks) {
        this.runner = Objects.requireNonNull(runner, "runner");
        this.checkpoints = Objects.requireNonNull(checkpoints, "checkpoints");
        this.eventLog = Objects.requireNonNull(eventLog, "eventLog");
        this.handoffs = Objects.requireNonNull(handoffs, "handoffs");
        this.locks = Objects.requireNonNull(locks, "locks");
    }

    public RunState start(String runId, GraphDefinition graph, HandoffNote handoff) {
        return start(runId, graph, Map.of(), handoff);
    }

    public RunState start(
            String runId,
            GraphDefinition graph,
            Map<String, String> initialValues,
            HandoffNote handoff) {
        try (RunLockManager.RunLease ignored = locks.acquire(runId)) {
            Objects.requireNonNull(graph, "graph");
            if (checkpoints.exists(runId)) {
                throw new ManagedRunException("run already exists: " + runId);
            }
            RunState state = runner.start(graph, initialValues);
            RunCheckpoint checkpoint = RunCheckpoint.current(
                    runId,
                    GraphSignature.calculate(graph),
                    CheckpointPhase.READY,
                    state);
            checkpoints.save(checkpoint);
            handoffs.save(runId, handoff);
            append(checkpoint, RunEventType.RUN_STARTED, "run initialized");
            if (state.status() == RunStatus.COMPLETED) {
                append(checkpoint, RunEventType.RUN_COMPLETED, "start node is terminal");
            }
            return state;
        }
    }

    public RunState step(String runId, GraphDefinition graph) {
        try (RunLockManager.RunLease ignored = locks.acquire(runId)) {
            RunCheckpoint checkpoint = loadForAction(runId);
            verifyGraph(checkpoint, graph);
            return advance(runner.prepare(graph), checkpoint).state();
        }
    }

    public RunState resume(String runId, GraphDefinition graph) {
        try (RunLockManager.RunLease ignored = locks.acquire(runId)) {
            RunCheckpoint checkpoint = loadForAction(runId);
            verifyGraph(checkpoint, graph);
            if (checkpoint.state().status() != RunStatus.RUNNING) {
                return checkpoint.state();
            }
            GraphRunner.PreparedGraph prepared = runner.prepare(graph);
            append(checkpoint, RunEventType.RUN_RESUMED, resumeDetail(checkpoint));
            RunCheckpoint current = checkpoint;
            while (current.state().status() == RunStatus.RUNNING) {
                current = advance(prepared, current);
            }
            return current.state();
        }
    }

    public RunState cancel(String runId) {
        try (RunLockManager.RunLease ignored = locks.acquire(runId)) {
            RunCheckpoint checkpoint = loadForAction(runId);
            RunState current = checkpoint.state();
            if (current.status() != RunStatus.RUNNING) {
                return current;
            }
            RunCheckpoint updated = cancelledCheckpoint(checkpoint);
            append(updated, RunEventType.RUN_CANCELLED, "run cancelled before node execution");
            checkpoints.save(updated);
            return updated.state();
        }
    }

    public RunInspection inspect(String runId) {
        CheckpointLoadResult loaded = checkpoints.load(runId);
        EventLogReadResult events = eventLog.readAll(runId);
        RunCheckpoint checkpoint = reconcileCancellation(loaded.checkpoint(), events.events());
        List<RunEvent> completeEventView = completedEventView(checkpoint, events.events());
        return new RunInspection(
                checkpoint.runId(),
                checkpoint.graphSignature(),
                checkpoint.phase(),
                checkpoint.state(),
                loaded.recoveredFromBackup(),
                completeEventView,
                events.ignoredIncompleteTail());
    }

    private RunCheckpoint advance(
            GraphRunner.PreparedGraph prepared,
            RunCheckpoint checkpoint) {
        if (checkpoint.state().status() != RunStatus.RUNNING) {
            return checkpoint;
        }
        RunCheckpoint started = RunCheckpoint.current(
                checkpoint.runId(),
                checkpoint.graphSignature(),
                CheckpointPhase.NODE_STARTED,
                checkpoint.state());
        checkpoints.save(started);
        append(started, RunEventType.NODE_STARTED, "node execution started");

        RunState updatedState = runner.step(prepared, checkpoint.state());
        RunCheckpoint updated = RunCheckpoint.current(
                checkpoint.runId(),
                checkpoint.graphSignature(),
                CheckpointPhase.READY,
                updatedState);
        checkpoints.save(updated);
        append(
                updated,
                RunEventType.NODE_COMPLETED,
                checkpoint.state().currentNode(),
                "node execution completed");
        if (updatedState.status() == RunStatus.COMPLETED) {
            append(updated, RunEventType.RUN_COMPLETED, "terminal node reached");
        }
        return updated;
    }

    private RunCheckpoint loadForAction(String runId) {
        CheckpointLoadResult loaded = checkpoints.load(runId);
        List<RunEvent> events = eventLog.readAll(runId).events();
        RunCheckpoint checkpoint = reconcileCancellation(loaded.checkpoint(), events);
        boolean cancellationRecovered = checkpoint != loaded.checkpoint();
        if (loaded.recoveredFromBackup() || cancellationRecovered) {
            checkpoints.save(checkpoint);
            String detail = cancellationRecovered
                    ? "cancelled state restored from durable event"
                    : "state restored from backup";
            append(checkpoint, RunEventType.CHECKPOINT_RECOVERED, detail);
        }
        reconcileCompletedEvents(checkpoint);
        return checkpoint;
    }

    private RunCheckpoint reconcileCancellation(
            RunCheckpoint checkpoint,
            List<RunEvent> events) {
        boolean cancellationRecorded = events.stream()
                .anyMatch(event -> event.type() == RunEventType.RUN_CANCELLED);
        if (!cancellationRecorded || checkpoint.state().status() == RunStatus.CANCELLED) {
            return checkpoint;
        }
        return cancelledCheckpoint(checkpoint);
    }

    private RunCheckpoint cancelledCheckpoint(RunCheckpoint checkpoint) {
        RunState current = checkpoint.state();
        RunState cancelled = new RunState(
                RunStatus.CANCELLED,
                current.currentNode(),
                current.executedSteps(),
                current.values(),
                current.path(),
                current.events());
        return RunCheckpoint.current(
                checkpoint.runId(),
                checkpoint.graphSignature(),
                CheckpointPhase.READY,
                cancelled);
    }

    private void reconcileCompletedEvents(RunCheckpoint checkpoint) {
        List<RunEvent> durableEvents = eventLog.readAll(checkpoint.runId()).events();
        List<RunEvent> completeEvents = completedEventView(checkpoint, durableEvents);
        for (int index = durableEvents.size(); index < completeEvents.size(); index++) {
            eventLog.append(completeEvents.get(index));
        }
    }

    private List<RunEvent> completedEventView(
            RunCheckpoint checkpoint,
            List<RunEvent> durableEvents) {
        List<RunEvent> completeEvents = new ArrayList<>(durableEvents);
        long sequence = completeEvents.isEmpty() ? 1 : completeEvents.getLast().sequence() + 1;
        for (TransitionEvent transition : checkpoint.state().events()) {
            boolean recorded = completeEvents.stream().anyMatch(event ->
                    event.type() == RunEventType.NODE_COMPLETED
                            && event.executedSteps() == transition.step()
                            && event.node().equals(transition.node()));
            if (!recorded) {
                RunStatus status = transition.step() == checkpoint.state().executedSteps()
                        ? checkpoint.state().status()
                        : RunStatus.RUNNING;
                completeEvents.add(RunEvent.current(
                        checkpoint.runId(),
                        sequence++,
                        RunEventType.NODE_COMPLETED,
                        transition.node(),
                        status,
                        transition.step(),
                        "node completion recovered from checkpoint"));
            }
        }
        if (checkpoint.state().status() == RunStatus.COMPLETED) {
            boolean completionRecorded = completeEvents.stream().anyMatch(event ->
                    event.type() == RunEventType.RUN_COMPLETED
                            && event.executedSteps() == checkpoint.state().executedSteps());
            if (!completionRecorded) {
                RunState state = checkpoint.state();
                completeEvents.add(RunEvent.current(
                        checkpoint.runId(),
                        sequence,
                        RunEventType.RUN_COMPLETED,
                        state.currentNode(),
                        state.status(),
                        state.executedSteps(),
                        "run completion recovered from checkpoint"));
            }
        }
        return List.copyOf(completeEvents);
    }

    private void verifyGraph(RunCheckpoint checkpoint, GraphDefinition graph) {
        Objects.requireNonNull(graph, "graph");
        String actual = GraphSignature.calculate(graph);
        if (!checkpoint.graphSignature().equals(actual)) {
            throw new ManagedRunException(
                    "graph does not match checkpoint for run " + checkpoint.runId());
        }
    }

    private void append(RunCheckpoint checkpoint, RunEventType type, String detail) {
        append(checkpoint, type, checkpoint.state().currentNode(), detail);
    }

    private void append(
            RunCheckpoint checkpoint,
            RunEventType type,
            NodeId node,
            String detail) {
        RunState state = checkpoint.state();
        append(
                checkpoint.runId(),
                type,
                node,
                state.status(),
                state.executedSteps(),
                detail);
    }

    private void append(
            String runId,
            RunEventType type,
            NodeId node,
            RunStatus status,
            int executedSteps,
            String detail) {
        List<RunEvent> events = eventLog.readAll(runId).events();
        long sequence = events.isEmpty() ? 1 : events.getLast().sequence() + 1;
        eventLog.append(RunEvent.current(
                runId,
                sequence,
                type,
                node,
                status,
                executedSteps,
                detail));
    }

    private static String resumeDetail(RunCheckpoint checkpoint) {
        return checkpoint.phase() == CheckpointPhase.NODE_STARTED
                ? "retrying node interrupted before a safe checkpoint"
                : "continuing from safe checkpoint";
    }
}
