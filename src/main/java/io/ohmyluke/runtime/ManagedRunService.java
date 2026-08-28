package io.ohmyluke.runtime;

import io.ohmyluke.graph.GraphDefinition;
import io.ohmyluke.graph.GraphRunner;
import io.ohmyluke.graph.NodeId;
import io.ohmyluke.graph.RunState;
import io.ohmyluke.graph.RunStatus;
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
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Coordinates graph execution with durable checkpoints, events, and handoff notes. */
public final class ManagedRunService {
    private final GraphRunner runner;
    private final CheckpointStore checkpoints;
    private final EventLogStore eventLog;
    private final HandoffStore handoffs;

    public ManagedRunService(
            GraphRunner runner,
            CheckpointStore checkpoints,
            EventLogStore eventLog,
            HandoffStore handoffs) {
        this.runner = Objects.requireNonNull(runner, "runner");
        this.checkpoints = Objects.requireNonNull(checkpoints, "checkpoints");
        this.eventLog = Objects.requireNonNull(eventLog, "eventLog");
        this.handoffs = Objects.requireNonNull(handoffs, "handoffs");
    }

    public RunState start(String runId, GraphDefinition graph, HandoffNote handoff) {
        return start(runId, graph, Map.of(), handoff);
    }

    public RunState start(
            String runId,
            GraphDefinition graph,
            Map<String, String> initialValues,
            HandoffNote handoff) {
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

    public RunState step(String runId, GraphDefinition graph) {
        RunCheckpoint checkpoint = loadForAction(runId);
        verifyGraph(checkpoint, graph);
        return advance(graph, checkpoint).state();
    }

    public RunState resume(String runId, GraphDefinition graph) {
        RunCheckpoint checkpoint = loadForAction(runId);
        verifyGraph(checkpoint, graph);
        if (checkpoint.state().status() != RunStatus.RUNNING) {
            return checkpoint.state();
        }
        append(checkpoint, RunEventType.RUN_RESUMED, resumeDetail(checkpoint));
        RunCheckpoint current = checkpoint;
        while (current.state().status() == RunStatus.RUNNING) {
            current = advance(graph, current);
        }
        return current.state();
    }

    public RunState cancel(String runId) {
        RunCheckpoint checkpoint = loadForAction(runId);
        RunState current = checkpoint.state();
        if (current.status() != RunStatus.RUNNING) {
            return current;
        }
        RunState cancelled = new RunState(
                RunStatus.CANCELLED,
                current.currentNode(),
                current.executedSteps(),
                current.values(),
                current.path(),
                current.events());
        RunCheckpoint updated = RunCheckpoint.current(
                checkpoint.runId(),
                checkpoint.graphSignature(),
                CheckpointPhase.READY,
                cancelled);
        checkpoints.save(updated);
        append(updated, RunEventType.RUN_CANCELLED, "run cancelled before node execution");
        return cancelled;
    }

    public RunInspection inspect(String runId) {
        CheckpointLoadResult loaded = checkpoints.load(runId);
        EventLogReadResult events = eventLog.readAll(runId);
        RunCheckpoint checkpoint = loaded.checkpoint();
        return new RunInspection(
                checkpoint.runId(),
                checkpoint.graphSignature(),
                checkpoint.phase(),
                checkpoint.state(),
                loaded.recoveredFromBackup(),
                events.events(),
                events.ignoredIncompleteTail());
    }

    private RunCheckpoint advance(GraphDefinition graph, RunCheckpoint checkpoint) {
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

        RunState updatedState = runner.step(graph, checkpoint.state());
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
        RunCheckpoint checkpoint = loaded.checkpoint();
        if (loaded.recoveredFromBackup()) {
            checkpoints.save(checkpoint);
            append(checkpoint, RunEventType.CHECKPOINT_RECOVERED, "state restored from backup");
        }
        return checkpoint;
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
        List<RunEvent> events = eventLog.readAll(checkpoint.runId()).events();
        long sequence = events.isEmpty() ? 1 : events.getLast().sequence() + 1;
        RunState state = checkpoint.state();
        eventLog.append(RunEvent.current(
                checkpoint.runId(),
                sequence,
                type,
                node,
                state.status(),
                state.executedSteps(),
                detail));
    }

    private static String resumeDetail(RunCheckpoint checkpoint) {
        return checkpoint.phase() == CheckpointPhase.NODE_STARTED
                ? "retrying node interrupted before a safe checkpoint"
                : "continuing from safe checkpoint";
    }
}
