package io.ohmyluke.preset;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import io.ohmyluke.ai.*;
import io.ohmyluke.state.ProjectPermissionManager;
import io.ohmyluke.state.ProjectPermissionStore;
import io.ohmyluke.tool.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PresetCommandValidationTest {
    @TempDir Path project;

    @Test void fixedOfflineCommandValidatesDisposableCopyAndLoopRepairsFailure() throws Exception {
        ProcessSandbox sandbox = PlatformProcessSandbox.detect();
        assumeTrue(sandbox.available() && Files.isExecutable(Path.of("/usr/bin/grep")), "requires platform sandbox and grep");
        Files.writeString(project.resolve("hello.txt"), "old");
        AtomicInteger calls = new AtomicInteger();
        var service = service(sandbox, calls);
        service.start("command", task());
        PresetResult result = service.resume("command");
        assertEquals(PresetStatus.SUCCEEDED, result.status(), result.toString());
        assertEquals(2, calls.get());
        assertEquals("ready", Files.readString(project.resolve("hello.txt")));
        assertTrue(Files.exists(project.resolve(".oml/runs/command/artifacts/preset-validate-3/result.json")));
    }

    @Test void unavailableSandboxIsBlockedNotPassedOrRetried() throws Exception {
        Files.writeString(project.resolve("hello.txt"), "old");
        AtomicInteger calls = new AtomicInteger();
        var service = service(new UnavailableProcessSandbox("fixture unavailable"), calls);
        service.start("unavailable", task());
        assertEquals(PresetStatus.BLOCKED, service.resume("unavailable").status());
        assertEquals(1, calls.get());
    }

    private TaskSpec task() {
        return new TaskSpec(1, "Make ready", "hello.txt", ExecutionMode.LOOP, 3, 0, 60_000, 2,
                new ValidationSpec(List.of(), List.of(), new ValidationCommand("/usr/bin/grep", List.of("-q", "ready", "hello.txt"), 0, 10_000)),
                null, null);
    }
    private PresetRunService service(ProcessSandbox sandbox, AtomicInteger calls) {
        Clock clock = Clock.systemUTC();
        return new PresetRunService(project, task -> new AiRuntime() {
            @Override public String fingerprint() { return "command-fixture:v1"; }
            @Override public AiRuntimeResult invoke(AiRequest request) {
                String content = calls.incrementAndGet() == 1 ? "wrong" : "ready";
                return AiRuntimeResult.success(PresetJson.encode(new EditProposal("hello.txt", content)), 0);
            }
        }, new ProjectPermissionManager(new ProjectPermissionStore(project), clock), sandbox, clock);
    }
}
