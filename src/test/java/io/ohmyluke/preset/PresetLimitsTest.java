package io.ohmyluke.preset;

import static org.junit.jupiter.api.Assertions.*;
import io.ohmyluke.ai.*;
import io.ohmyluke.policy.ToolPermissionDecision;
import io.ohmyluke.tool.UnavailableProcessSandbox;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PresetLimitsTest {
    @TempDir Path project;

    @Test void measuredUsageLimitStopsBeforeApplyingAndCannotBeResetByResume() throws Exception {
        Files.writeString(project.resolve("hello.txt"), "old");
        AtomicInteger calls = new AtomicInteger();
        var service = service(AiRuntimeResult.success("{\"path\":\"hello.txt\",\"content\":\"ready\"}",
                AiTokenUsage.measured(10, 0, 2, 0, "fixture")), calls, Clock.systemUTC());
        service.start("usage", task(10));
        assertEquals(PresetStatus.LIMIT_REACHED, service.resume("usage").status());
        assertEquals("limit.usage", service.resume("usage").reason());
        assertEquals("old", Files.readString(project.resolve("hello.txt")));
        assertEquals(1, calls.get());
    }

    @Test void missingUsageWithBudgetFailsClosedInsteadOfCountingItAsFree() throws Exception {
        Files.writeString(project.resolve("hello.txt"), "old");
        var service = service(AiRuntimeResult.success("{\"path\":\"hello.txt\",\"content\":\"ready\"}", 0),
                new AtomicInteger(), Clock.systemUTC());
        service.start("unknown", task(100));
        assertEquals("usage-unavailable", service.resume("unknown").reason());
        assertEquals(PresetStatus.BLOCKED, service.inspect("unknown").status());
        assertFalse(service.inspect("unknown").allTokenUsageAvailable());
        assertEquals("old", Files.readString(project.resolve("hello.txt")));
    }

    @Test void elapsedTimeIncludesDowntimeAndStopsBeforeAnotherNode() throws Exception {
        Files.writeString(project.resolve("hello.txt"), "old");
        AtomicInteger calls = new AtomicInteger();
        Clock start = Clock.fixed(Instant.parse("2026-08-31T00:00:00Z"), ZoneOffset.UTC);
        AiRuntimeResult response = AiRuntimeResult.success("unused", 0);
        service(response, calls, start).start("elapsed", task(0));
        var later = service(response, calls, Clock.offset(start, Duration.ofMinutes(2)));
        assertEquals("limit.elapsed-time", later.resume("elapsed").reason());
        assertEquals(0, calls.get());
    }

    @Test void runtimeFailureIsNotRetriedAsIfItWereAFileValidationFailure() throws Exception {
        Files.writeString(project.resolve("hello.txt"), "old");
        AtomicInteger calls = new AtomicInteger();
        var service = service(AiRuntimeResult.failure(AiFailureCode.INVALID_RESPONSE, 0), calls, Clock.systemUTC());
        service.start("provider", task(0));
        assertEquals(PresetStatus.BLOCKED, service.resume("provider").status());
        assertEquals(1, calls.get());
    }

    private PresetRunService service(AiRuntimeResult response, AtomicInteger calls, Clock clock) {
        return new PresetRunService(project, task -> new AiRuntime() {
            @Override public String fingerprint() { return "limits:v1"; }
            @Override public AiRuntimeResult invoke(AiRequest request) { calls.incrementAndGet(); return response; }
        }, request -> ToolPermissionDecision.allow("test.allow", "allowed", null),
                new UnavailableProcessSandbox("fixture"), clock);
    }
    private TaskSpec task(long usage) {
        return new TaskSpec(1, "Make ready", "hello.txt", ExecutionMode.LOOP, 3, usage, 60_000, 2,
                new ValidationSpec(List.of("ready"), List.of(), null), null, null);
    }
}
