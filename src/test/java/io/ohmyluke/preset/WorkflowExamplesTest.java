package io.ohmyluke.preset;

import static org.junit.jupiter.api.Assertions.*;
import io.ohmyluke.ai.*;
import io.ohmyluke.policy.ToolPermissionDecision;
import io.ohmyluke.tool.UnavailableProcessSandbox;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkflowExamplesTest {
    @TempDir Path project;

    @Test void checkedInExamplesAreRunnableAndAiFreeExampleDoesNotEvenConstructRuntime() throws Exception {
        Files.copy(Path.of("examples/workflows/ready.txt"), project.resolve("hello.txt"));
        var runs = service(new AtomicInteger(), true);
        runs.start("no-ai", PresetJson.decode(Files.readString(Path.of("examples/workflows/check-and-approve.json")), WorkflowSpec.class));
        var waiting = runs.resume("no-ai");
        assertEquals(WorkflowStatus.WAITING_APPROVAL, waiting.status());
        runs.decideApproval("no-ai", waiting.approval().requestId(), true);
        assertEquals(WorkflowStatus.SUCCEEDED, runs.resume("no-ai").status());
    }

    @Test void editExampleUsesExistingRuntimeContractAndReusesSavedProposal() throws Exception {
        Files.writeString(project.resolve("hello.txt"), "old");
        AtomicInteger calls = new AtomicInteger();
        var runs = service(calls, false);
        runs.start("example", PresetJson.decode(Files.readString(Path.of("examples/workflows/edit-with-approval.json")), WorkflowSpec.class));
        var waiting = runs.resume("example");
        assertEquals(WorkflowStatus.WAITING_APPROVAL, waiting.status());
        assertEquals(1, calls.get());
        runs.decideApproval("example", waiting.approval().requestId(), true);
        assertEquals(WorkflowStatus.SUCCEEDED, service(calls, false).resume("example").status());
        assertEquals(1, calls.get());
    }

    private WorkflowRunService service(AtomicInteger calls, boolean forbidAi) {
        return new WorkflowRunService(project, task -> {
            if (forbidAi) { throw new AssertionError("AI-free workflow must not resolve a runtime"); }
            return new AiRuntime() {
                public String fingerprint() { return "example:v1"; }
                public AiRuntimeResult invoke(AiRequest request) {
                    calls.incrementAndGet();
                    return AiRuntimeResult.success("{\"path\":\"hello.txt\",\"content\":\"OML_READY\"}", 10);
                }
            };
        }, request -> ToolPermissionDecision.allow("test.allow", "allowed", null), new UnavailableProcessSandbox("fixture"), Clock.systemUTC());
    }
}
