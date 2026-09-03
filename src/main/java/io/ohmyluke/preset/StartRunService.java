package io.ohmyluke.preset;

import io.ohmyluke.policy.ToolPermissionEvaluator;
import io.ohmyluke.tool.FileTool;
import io.ohmyluke.tool.FileToolRequest;
import io.ohmyluke.tool.FileToolResult;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Objects;

/** Bounded new-start input and persistence; execution and resume stay in the existing services. */
public final class StartRunService {
    private final Path project;
    private final PresetRunService presets;
    private final WorkflowRunService workflows;
    private final ToolPermissionEvaluator permissions;
    private final Clock clock;

    public StartRunService(Path project, PresetRunService presets, WorkflowRunService workflows,
                           ToolPermissionEvaluator permissions, Clock clock) {
        this.project = Objects.requireNonNull(project, "project");
        this.presets = Objects.requireNonNull(presets, "presets");
        this.workflows = Objects.requireNonNull(workflows, "workflows");
        this.permissions = Objects.requireNonNull(permissions, "permissions");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public StartSpec readSpec(Path path) {
        String relativePath = TaskSpec.relativeFile(Objects.requireNonNull(path, "path").toString());
        FileToolResult input = new FileTool(project, "start-input", permissions, clock)
                .execute(FileToolRequest.read("start-input", path));
        if (!input.executed() || input.content().length > 512 * 1024) {
            throw new IllegalArgumentException("cannot read bounded start file");
        }
        StartSpec spec = PresetJson.decode(new String(input.content(), StandardCharsets.UTF_8), StartSpec.class);
        boolean editsContract = spec.task() != null ? spec.task().file().equals(relativePath)
                : spec.workflow().steps().stream().anyMatch(step -> step.task() != null && step.task().file().equals(relativePath));
        if (editsContract) { throw new IllegalArgumentException("start contract cannot be the editable target"); }
        return spec;
    }

    public void start(String runId, StartPlan plan) {
        Objects.requireNonNull(plan, "plan");
        if (plan.task() != null) { presets.start(runId, plan.task(), plan.selection()); }
        else { workflows.start(runId, plan.workflow(), plan.selection()); }
    }
}
