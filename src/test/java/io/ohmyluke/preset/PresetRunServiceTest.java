package io.ohmyluke.preset;

import static org.junit.jupiter.api.Assertions.*;

import io.ohmyluke.ai.*;
import io.ohmyluke.policy.ToolPermissionDecision;
import io.ohmyluke.tool.UnavailableProcessSandbox;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PresetRunServiceTest {
    @TempDir Path project;

    @Test void directAppliesOneProposalAndRequiresValidation() throws Exception {
        Files.writeString(project.resolve("hello.txt"), "old");
        ScriptedAi ai = new ScriptedAi("ready");
        PresetRunService service = service(ai);
        service.start("direct", task(ExecutionMode.DIRECT, 1));
        PresetResult result = service.resume("direct");
        assertEquals(PresetStatus.SUCCEEDED, result.status());
        assertEquals(1, result.attempts());
        assertEquals(1, ai.requests.size());
        assertEquals("ready", Files.readString(project.resolve("hello.txt")));
        assertTrue(Files.exists(project.resolve(".oml/runs/direct")));
        assertEquals(PresetStatus.SUCCEEDED, service(ai).resume("direct").status());
        assertEquals(1, ai.requests.size(), "completed resume must not call AI again");
    }

    @Test void directFailureDoesNotRetryOrClaimSuccess() throws Exception {
        Files.writeString(project.resolve("hello.txt"), "old");
        ScriptedAi ai = new ScriptedAi("still wrong");
        PresetRunService service = service(ai);
        service.start("direct-fail", task(ExecutionMode.DIRECT, 1));
        assertEquals(PresetStatus.VALIDATION_FAILED, service.resume("direct-fail").status());
        assertEquals(1, ai.requests.size());
    }

    @Test void loopPassesOnlyCurrentFileAndLatestFailureToNextWriter() throws Exception {
        Files.writeString(project.resolve("hello.txt"), "old");
        ScriptedAi ai = new ScriptedAi("wrong", "ready");
        PresetRunService service = service(ai);
        service.start("loop", task(ExecutionMode.LOOP, 3));
        PresetResult result = service.resume("loop");
        assertEquals(PresetStatus.SUCCEEDED, result.status());
        assertEquals(2, result.attempts());
        assertEquals("wrong", ai.requests.get(1).context().get("currentContent"));
        assertTrue(ai.requests.get(1).context().get("lastFailure").contains("required-text"));
        assertFalse(ai.requests.get(1).context().containsKey("history"));
        assertFalse(ai.requests.get(1).context().containsKey("preset.task"));
    }

    @Test void repeatedFailureStopsAcrossSuccessfulWriterAndApplyNodes() throws Exception {
        Files.writeString(project.resolve("hello.txt"), "old");
        ScriptedAi ai = new ScriptedAi("wrong", "also wrong", "ready");
        PresetRunService service = service(ai);
        service.start("repeat", task(ExecutionMode.LOOP, 5));
        PresetResult result = service.resume("repeat");
        assertEquals(PresetStatus.LIMIT_REACHED, result.status());
        assertEquals("repeated-failure", result.reason());
        assertEquals(2, ai.requests.size());
    }

    @Test void attemptBudgetStopsBeforeAnotherAiCall() throws Exception {
        Files.writeString(project.resolve("hello.txt"), "old");
        ScriptedAi ai = new ScriptedAi("wrong");
        PresetRunService service = service(ai);
        service.start("limit", task(ExecutionMode.LOOP, 1));
        assertEquals("attempt-limit", service.resume("limit").reason());
        assertEquals(1, ai.requests.size());
    }

    @Test void externalEditBetweenProposalAndApplyIsPreserved() throws Exception {
        Files.writeString(project.resolve("hello.txt"), "old");
        ScriptedAi ai = new ScriptedAi("ready");
        PresetRunService service = service(ai);
        service.start("conflict", task(ExecutionMode.DIRECT, 1));
        service.step("conflict"); // prepare
        service.step("conflict"); // writer
        Files.writeString(project.resolve("hello.txt"), "user edit");
        assertEquals(PresetStatus.BLOCKED, service.resume("conflict").status());
        assertEquals("user edit", Files.readString(project.resolve("hello.txt")));
    }

    @Test void newServiceRestoresContractAndPendingProposalWithoutRecallingWriter() throws Exception {
        Files.writeString(project.resolve("hello.txt"), "old");
        ScriptedAi ai = new ScriptedAi("ready");
        PresetRunService service = service(ai);
        service.start("resume", task(ExecutionMode.DIRECT, 1));
        service.step("resume");
        service.step("resume");
        assertEquals(PresetStatus.SUCCEEDED, service(ai).resume("resume").status());
        assertEquals(1, ai.requests.size());
    }

    @Test void malformedOrOutOfScopeProposalCannotWriteAnotherFile() throws Exception {
        Files.writeString(project.resolve("hello.txt"), "old");
        for (String response : List.of("done!", "{\"path\":\".oml/policy.json\",\"content\":\"ready\"}",
                "{\"path\":\"hello.txt\",\"content\":\"ready\",\"maxAttempts\":100}")) {
            ScriptedAi ai = new ScriptedAi();
            ai.raw = response;
            PresetRunService service = service(ai);
            String id = "invalid-" + Math.abs(response.hashCode());
            service.start(id, task(ExecutionMode.DIRECT, 1));
            assertNotEquals(PresetStatus.SUCCEEDED, service.resume(id).status());
            assertEquals("old", Files.readString(project.resolve("hello.txt")));
        }
    }

    @Test void dangerousTargetIsRejectedBeforeAiInvocation() throws Exception {
        ScriptedAi ai = new ScriptedAi("ready");
        for (String path : List.of("../outside.txt", ".oml/permissions.json", ".env", ".git/config")) {
            assertThrows(IllegalArgumentException.class, () -> taskWithFile(path));
        }
        assertTrue(ai.requests.isEmpty());
    }

    private TaskSpec taskWithFile(String file) {
        return new TaskSpec(1, "Make the file ready", file, ExecutionMode.DIRECT, 1,
                0, 60_000, 2, new ValidationSpec(List.of("ready"), List.of(), null), null, null);
    }

    private TaskSpec task(ExecutionMode mode, int attempts) {
        return new TaskSpec(1, "Make the file ready", "hello.txt", mode, attempts,
                0, 60_000, 2, new ValidationSpec(List.of("ready"), List.of(), null), null, null);
    }

    private PresetRunService service(ScriptedAi ai) {
        return new PresetRunService(project, task -> ai,
                request -> ToolPermissionDecision.allow("test.allow", "allowed", null),
                new UnavailableProcessSandbox("test sandbox unavailable"), Clock.systemUTC());
    }

    private static final class ScriptedAi implements AiRuntime {
        final List<AiRequest> requests = new ArrayList<>();
        final List<String> responses;
        String raw;
        ScriptedAi(String... responses) { this.responses = List.of(responses); }
        @Override public String fingerprint() { return "scripted:v1"; }
        @Override public AiRuntimeResult invoke(AiRequest request) {
            requests.add(request);
            String output = raw != null ? raw : PresetJson.encode(
                    new EditProposal("hello.txt", responses.get(requests.size() - 1)));
            return AiRuntimeResult.success(output, 10);
        }
    }
}
