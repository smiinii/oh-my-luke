package io.ohmyluke.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class ToolPermissionPolicyTest {
    private static final Instant NOW = Instant.parse("2026-08-28T00:00:00Z");
    private static final Path PROJECT = Path.of("/workspace/oh-my-luke");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void allowsReversibleProjectWorkWithoutApproval() {
        ToolPermissionPolicy policy = policy(false, List.of());

        ToolPermissionDecision read = policy.evaluate(request("read-1", ToolCapability.PROJECT_READ, "src/App.java"));
        ToolPermissionDecision write = policy.evaluate(request("write-1", ToolCapability.PROJECT_WRITE, "src/App.java"));
        ToolPermissionDecision test = policy.evaluate(request("test-1", ToolCapability.LOCAL_PROCESS, "./gradlew test"));

        assertEquals(ToolPermission.ALLOW, read.permission());
        assertEquals(ToolPermission.ALLOW, write.permission());
        assertEquals(ToolPermission.ALLOW, test.permission());
        assertEquals("permission.default-allow", test.reasonCode());
    }

    @Test
    void asksForNewExternalOrHighImpactWork() {
        ToolPermissionPolicy policy = policy(false, List.of());

        assertEquals(
                ToolPermission.ASK,
                policy.evaluate(request("push-1", ToolCapability.EXTERNAL_WRITE, "git:origin")).permission());
        assertEquals(
                ToolPermission.ASK,
                policy.evaluate(request("deps-1", ToolCapability.DEPENDENCY_INSTALL, "repo.maven.apache.org")).permission());
        assertEquals(
                ToolPermission.ASK,
                policy.evaluate(request("delete-1", ToolCapability.BULK_DELETE, "generated/")).permission());
    }

    @Test
    void neverLetsAutonomyOrAStoredGrantOverrideSafetyInvariants() {
        ToolPermissionRequest mutation = request(
                "policy-1",
                ToolCapability.POLICY_MUTATION,
                ".oml/permissions.json");
        ToolPermissionGrant projectGrant = ToolPermissionGrant.forProject(
                "grant-1",
                mutation,
                NOW.plusSeconds(60).toEpochMilli());
        ToolPermissionPolicy policy = policy(true, List.of(projectGrant));

        ToolPermissionDecision decision = policy.evaluate(mutation);

        assertEquals(ToolPermission.DENY, decision.permission());
        assertEquals("permission.invariant-deny", decision.reasonCode());
        assertNull(decision.grantId());
    }

    @Test
    void autonomousProjectModeAllowsAskClassActionsButNotInvariants() {
        ToolPermissionPolicy policy = policy(true, List.of());

        ToolPermissionDecision push = policy.evaluate(request(
                "push-1",
                ToolCapability.EXTERNAL_WRITE,
                "git:origin"));

        assertEquals(ToolPermission.ALLOW, push.permission());
        assertEquals("permission.autonomous-project", push.reasonCode());
    }

    @Test
    void consumesAOneTimeGrantExactlyOnce() {
        ToolPermissionRequest request = request("push-1", ToolCapability.EXTERNAL_WRITE, "git:origin");
        ToolPermissionGrant grant = ToolPermissionGrant.once(
                "grant-1",
                request,
                NOW.plusSeconds(60).toEpochMilli());
        ToolPermissionPolicy policy = policy(false, List.of(grant));

        ToolPermissionDecision first = policy.evaluate(request);
        ToolPermissionDecision second = policy.evaluate(request);

        assertEquals(ToolPermission.ALLOW, first.permission());
        assertEquals("grant-1", first.grantId());
        assertEquals(ToolPermission.ASK, second.permission());
    }

    @Test
    void runGrantMatchesOnlyTheSameRunCapabilityAndTarget() {
        ToolPermissionRequest approved = request("push-1", ToolCapability.EXTERNAL_WRITE, "git:origin");
        ToolPermissionGrant grant = ToolPermissionGrant.forRun(
                "grant-1",
                approved,
                NOW.plusSeconds(60).toEpochMilli());
        ToolPermissionPolicy policy = policy(false, List.of(grant));

        ToolPermissionDecision sameRun = policy.evaluate(request(
                "push-2",
                ToolCapability.EXTERNAL_WRITE,
                "git:origin"));
        ToolPermissionDecision otherTarget = policy.evaluate(request(
                "push-3",
                ToolCapability.EXTERNAL_WRITE,
                "git:upstream"));
        ToolPermissionDecision otherRun = policy.evaluate(new ToolPermissionRequest(
                "push-4",
                "run-002",
                PROJECT,
                ToolCapability.EXTERNAL_WRITE,
                "git:origin"));

        assertEquals(ToolPermission.ALLOW, sameRun.permission());
        assertEquals(ToolPermission.ASK, otherTarget.permission());
        assertEquals(ToolPermission.ASK, otherRun.permission());
    }

    @Test
    void projectGrantSurvivesRunsButNotProjectsTargetsOrExpiry() {
        ToolPermissionRequest approved = request("push-1", ToolCapability.EXTERNAL_WRITE, "git:origin");
        ToolPermissionGrant grant = ToolPermissionGrant.forProject(
                "grant-1",
                approved,
                NOW.plusSeconds(60).toEpochMilli());
        ToolPermissionPolicy policy = policy(false, List.of(grant));

        ToolPermissionDecision nextRun = policy.evaluate(new ToolPermissionRequest(
                "push-2",
                "run-002",
                PROJECT,
                ToolCapability.EXTERNAL_WRITE,
                "git:origin"));
        ToolPermissionDecision otherProject = policy.evaluate(new ToolPermissionRequest(
                "push-3",
                "run-002",
                Path.of("/workspace/other"),
                ToolCapability.EXTERNAL_WRITE,
                "git:origin"));
        ToolPermissionDecision expired = policy(false, List.of(ToolPermissionGrant.forProject(
                        "grant-expired",
                        approved,
                        NOW.toEpochMilli())))
                .evaluate(approved);

        assertEquals(ToolPermission.ALLOW, nextRun.permission());
        assertEquals(ToolPermission.ASK, otherProject.permission());
        assertEquals(ToolPermission.ASK, expired.permission());
    }

    private static ToolPermissionPolicy policy(boolean autonomous, List<ToolPermissionGrant> grants) {
        return new ToolPermissionPolicy(new PermissionGrantLedger(grants), autonomous, CLOCK);
    }

    private static ToolPermissionRequest request(
            String operationId,
            ToolCapability capability,
            String target) {
        return new ToolPermissionRequest(operationId, "run-001", PROJECT, capability, target);
    }
}
