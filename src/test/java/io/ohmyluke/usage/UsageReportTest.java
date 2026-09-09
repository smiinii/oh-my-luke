package io.ohmyluke.usage;

import static org.junit.jupiter.api.Assertions.*;
import io.ohmyluke.ai.*;
import io.ohmyluke.preset.*;
import io.ohmyluke.tool.*;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UsageReportTest {
    @TempDir Path project;
    final Map<String, UsageReport.Evidence> evidence = new HashMap<>();

    @Test void retriesAreGroupedOnceAndSubsetsAreNotAddedAgainAfterResume() throws Exception {
        var service = service("wrong", "ready");
        service.start("question", task());
        var result = service.resume("question");
        assertEquals(PresetStatus.SUCCEEDED, result.status(), result.toString());
        var report = reports().read("question");
        assertEquals("통과", report.status());
        assertEquals(2, report.attempts().size());
        assertEquals("220", report.recordedTotal());
        assertEquals("200", report.input());
        assertEquals("80", report.cachedInput());
        assertEquals("20", report.output());
        assertEquals("6", report.reasoningOutput());
        assertTrue(report.completeReportedUsage());
        service.resume("question");
        assertEquals(report, reports().read("question"));
    }

    @Test void completedAnswerIsNotValidationPassAndMissingEvidenceIsNotZero() throws Exception {
        var service = service("ready");
        service.start("pending", task());
        service.step("pending"); service.step("pending");
        assertEquals(1, evidence.size(), service.inspect("pending").toString());
        assertEquals("완료 · 검증 전", reports().read("pending").status());
        evidence.clear();
        var missing = reports().read("pending");
        assertFalse(missing.completeReportedUsage());
        assertNull(missing.recordedTotal());
        assertNull(missing.attempts().getFirst().total());
    }

    @Test void absentHistoryDoesNotCreateDirectoriesAndOtherProjectsAreNotListed() throws Exception {
        assertEquals(List.of(), reports().list());
        assertFalse(Files.exists(project.resolve(".oml")));
        var service = service("ready"); service.start("local", task()); service.resume("local");
        Path other = Files.createDirectory(project.resolve("other"));
        assertTrue(new UsageReport(other, id -> evidence.get(id)).list().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> reports().read("../local"));
    }

    @Test void sameThreadAcrossDifferentCallsIsNotBlindlyAddedAndMismatchIsUnavailable() throws Exception {
        var service = service("wrong", "ready"); service.start("duplicate", task()); service.resume("duplicate");
        evidence.replaceAll((id, value) -> new UsageReport.Evidence(value.tokens(), "same-thread", "SUCCESS"));
        var duplicate = reports().read("duplicate");
        assertFalse(duplicate.completeReportedUsage());
        assertNull(duplicate.recordedTotal());
        evidence.replaceAll((id, value) -> new UsageReport.Evidence(AiTokenUsage.measured(1,0,0,0,"codex-exec-jsonl"), id, "SUCCESS"));
        assertNull(reports().read("duplicate").recordedTotal());
    }

    @Test void symlinkedAndHardLinkedStateAreRejectedWithoutReadingTheTarget() throws Exception {
        var service = service("ready"); service.start("linked", task());
        Path state = project.resolve(".oml/runs/linked/state.json");
        Path original = Files.move(state, project.resolve("saved.json"));
        Files.createSymbolicLink(state, original);
        assertThrows(IllegalArgumentException.class, () -> reports().read("linked"));
        Files.delete(state); Files.createLink(state, original);
        assertThrows(IllegalArgumentException.class, () -> reports().read("linked"));
    }

    @Test void activeRunIsNotReadAsAConsistentSnapshot() throws Exception {
        var service=service("ready"); service.start("active",task());
        try(var lease=new io.ohmyluke.state.RunLockManager(project).acquire("active")) {
            assertThrows(IllegalArgumentException.class,()->reports().read("active"));
        }
        assertEquals("0",reports().read("active").recordedTotal());
    }

    @Test void reportedZeroIsDifferentFromUnavailableAndNumbersAboveJavascriptPrecisionStayExact() throws Exception {
        var zero=serviceWithUsage(AiTokenUsage.measured(0,0,0,0,"codex-exec-jsonl"),"ready");
        zero.start("zero",task());zero.resume("zero");
        assertEquals("0",reports().read("zero").recordedTotal());assertTrue(reports().read("zero").completeReportedUsage());
        var large=serviceWithUsage(AiTokenUsage.measured(9_007_199_254_740_993L,0,7,0,"codex-exec-jsonl"),"ready");
        large.start("large",task());large.resume("large");
        assertEquals("9007199254741000",reports().read("large").recordedTotal());
    }

    @Test void missingLifecycleLogCannotClaimCompleteAccounting() throws Exception {
        var service=service("ready");service.start("missing-log",task());service.resume("missing-log");
        Files.delete(project.resolve(".oml/runs/missing-log/events.jsonl"));
        var report=reports().read("missing-log");
        assertEquals("110",report.recordedTotal());assertFalse(report.completeReportedUsage());
        assertFalse(Files.exists(project.resolve(".oml/runs/missing-log/events.jsonl")));
    }

    @Test void failedInvocationStillContributesItsReportedUsage() throws Exception {
        Files.writeString(project.resolve("hello.txt"),"old");
        AiRuntime ai=new AiRuntime(){
            public String fingerprint(){return "test:failure";}
            public AiRuntimeResult invoke(AiRequest request){
                var tokens=AiTokenUsage.measured(10,0,2,0,"codex-exec-jsonl");
                evidence.put(request.invocationId(),new UsageReport.Evidence(tokens,"failed-session","FAILURE"));
                return AiRuntimeResult.failure(AiFailureCode.EXECUTION_FAILED,tokens,"failed-session");
            }
        };
        var service=new PresetRunService(project,t->ai,request->io.ohmyluke.policy.ToolPermissionDecision.allow("test.allow","allowed",null),
                new UnavailableProcessSandbox("test"),Clock.systemUTC());
        service.start("failed",task());service.resume("failed");
        var report=reports().read("failed");assertEquals("12",report.recordedTotal());
        assertEquals("FAILURE",report.attempts().getFirst().response());assertNotEquals("통과",report.status());
    }

    @Test void workflowEditNodesUseTheirOriginalInvocationIdentityAndShareOneQuestion() throws Exception {
        Files.writeString(project.resolve("hello.txt"),"old");
        AiRuntime ai=new AiRuntime() {
            public String fingerprint(){return "workflow-usage:test";}
            public AiRuntimeResult invoke(AiRequest request){
                var usage=AiTokenUsage.measured(100,40,10,3,"codex-exec-jsonl");
                evidence.put(request.invocationId(),new UsageReport.Evidence(usage,request.invocationId(),"SUCCESS"));
                return AiRuntimeResult.success(PresetJson.encode(Map.of("path","hello.txt","content","ready")),usage);
            }
        };
        var service=new WorkflowRunService(project,t->ai,
                request->io.ohmyluke.policy.ToolPermissionDecision.allow("test.allow","allowed",null),
                new UnavailableProcessSandbox("test"),Clock.systemUTC());
        var spec=new WorkflowSpec(1,"Workflow question","first",List.of(
                WorkflowStep.edit("first",task(),false,"second","stopped"),
                WorkflowStep.edit("second",task(),false,"succeeded","stopped")),100,0,60_000);
        service.start("workflow",spec); service.resume("workflow");
        var report=reports().read("workflow");
        assertEquals("Workflow question",report.question()); assertEquals("통과",report.status());
        assertEquals("220",report.recordedTotal()); assertTrue(report.completeReportedUsage());
        assertEquals(List.of("first.writer","second.writer"),report.attempts().stream().map(UsageReport.Attempt::node).toList());
    }

    @Test void missingTailAndBackupRecoveryAreFlaggedWithoutRepairingFiles() throws Exception {
        var service=service("ready");service.start("recovery",task());service.resume("recovery");
        Path log=project.resolve(".oml/runs/recovery/events.jsonl");
        Files.writeString(log,"{\"unfinished\":",StandardOpenOption.APPEND);
        String before=Files.readString(log);
        assertFalse(reports().read("recovery").completeReportedUsage());assertEquals(before,Files.readString(log));
        Path state=project.resolve(".oml/runs/recovery/state.json");Files.writeString(state,"broken");
        assertFalse(reports().read("recovery").completeReportedUsage());assertEquals("broken",Files.readString(state));
    }

    @Test void realStoredCodexResultMatchesTheQuestionAndSurvivesAReadOnlyRestart() throws Exception {
        Files.writeString(project.resolve("hello.txt"),"old");
        Path executable=project.resolve("fake-codex");
        Files.writeString(executable,"""
                #!/bin/sh
                /bin/cat >/dev/null
                printf '%s\\n' '{"type":"thread.started","thread_id":"fixture-thread"}' \
                  '{"type":"item.completed","item":{"type":"agent_message","text":"{\\"path\\":\\"hello.txt\\",\\"content\\":\\"ready\\"}"}}' \
                  '{"type":"turn.completed","usage":{"input_tokens":100,"cached_input_tokens":40,"output_tokens":10,"reasoning_output_tokens":3}}'
                """);
        assertTrue(executable.toFile().setExecutable(true,true));
        var service=new PresetRunService(project,t->new io.ohmyluke.ai.codex.CodexCliRuntime(
                io.ohmyluke.ai.codex.CodexCliConfiguration.forExecutable(project,executable)),
                request->io.ohmyluke.policy.ToolPermissionDecision.allow("test.allow","allowed",null),
                new UnavailableProcessSandbox("test"),Clock.systemUTC());
        service.start("stored",task());assertEquals(PresetStatus.SUCCEEDED,service.resume("stored").status());
        var reader=new UsageReport(project,new io.ohmyluke.ai.codex.CodexUsageReader(project.toRealPath()));
        assertEquals("110",reader.read("stored").recordedTotal());
        assertTrue(reader.read("stored").completeReportedUsage());
        service.resume("stored");assertEquals(1,reader.read("stored").attempts().size());
    }

    private UsageReport reports() { return new UsageReport(project, id -> evidence.get(id)); }
    private TaskSpec task() {
        return new TaskSpec(1, "Make the greeting ready", "hello.txt", ExecutionMode.LOOP, 3, 0, 60_000, 3,
                new ValidationSpec(List.of("ready"), List.of(), null), "test-model", null);
    }
    private PresetRunService service(String... contents) throws Exception {
        return serviceWithUsage(AiTokenUsage.measured(100,40,10,3,"codex-exec-jsonl"),contents);
    }
    private PresetRunService serviceWithUsage(AiTokenUsage tokens,String... contents) throws Exception {
        Files.writeString(project.resolve("hello.txt"), "old");
        var queue = new ArrayDeque<>(List.of(contents));
        AiRuntime ai = new AiRuntime() {
            public String fingerprint() { return "usage-fixture:v1"; }
            public AiRuntimeResult invoke(AiRequest request) {
                evidence.put(request.invocationId(), new UsageReport.Evidence(tokens, request.invocationId(), "SUCCESS"));
                return AiRuntimeResult.success(PresetJson.encode(Map.of("path", "hello.txt", "content", queue.remove())), tokens);
            }
        };
        return new PresetRunService(project, task -> ai, request -> io.ohmyluke.policy.ToolPermissionDecision.allow("test.allow", "allowed", null),
                new UnavailableProcessSandbox("test"), Clock.systemUTC());
    }
}
