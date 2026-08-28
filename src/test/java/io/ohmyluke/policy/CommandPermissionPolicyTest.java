package io.ohmyluke.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class CommandPermissionPolicyTest {
    private static final Instant NOW = Instant.parse("2026-08-28T00:00:00Z");
    private static final Path PROJECT = Path.of("/workspace/project");
    private static final String GIT = "/usr/bin/git";
    private static final String GRADLE = "/opt/oml/bin/gradle";
    private static final String DELETE = "/opt/oml/bin/safe-delete";

    private final CommandPermissionPolicy policy = new CommandPermissionPolicy(
            List.of(
                    new CommandRule(GIT, List.of("status"), CommandRisk.READ_ONLY),
                    new CommandRule(GIT, List.of("diff"), CommandRisk.READ_ONLY),
                    new CommandRule(GIT, List.of("add", "file.txt"), CommandRisk.PROJECT_WRITE),
                    new CommandRule(GIT, List.of("push", "origin"), CommandRisk.EXTERNAL_CHANGE),
                    new CommandRule(GRADLE, List.of("test"), CommandRisk.PROJECT_WRITE),
                    new CommandRule(DELETE, List.of("file.txt"), CommandRisk.DESTRUCTIVE)),
            "run-001",
            PROJECT,
            Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void permitsOnlyAnExactAllowlistedExecutableAndArgumentList() {
        PolicyDecision allowed = policy.evaluate(new CommandInvocation(GIT, List.of("status")), null);
        PolicyDecision suffix = policy.evaluate(new CommandInvocation(GIT, List.of("status", "--short")), null);
        PolicyDecision dangerousSuffix = policy.evaluate(
                new CommandInvocation(GRADLE, List.of("test", "--init-script", "/tmp/evil.gradle")),
                null);
        PolicyDecision unknown = policy.evaluate(
                new CommandInvocation("/usr/bin/curl", List.of("https://example.com")),
                null);

        assertEquals(PolicyOutcome.CONTINUE, allowed.outcome());
        assertEquals("command.allowed", allowed.reasonCode());
        assertEquals("command.not-allowed", suffix.reasonCode());
        assertEquals("command.not-allowed", dangerousSuffix.reasonCode());
        assertEquals("command.not-allowed", unknown.reasonCode());
    }

    @Test
    void neverPermitsACommandShellOrWrapperEvenIfAllowlisted() {
        CommandPermissionPolicy misconfigured = new CommandPermissionPolicy(List.of(
                new CommandRule("/bin/bash", List.of("-c", "git status"), CommandRisk.READ_ONLY),
                new CommandRule("/usr/bin/env", List.of("bash", "-c", "git status"), CommandRisk.READ_ONLY)));

        PolicyDecision shell = misconfigured.evaluate(
                new CommandInvocation("/bin/bash", List.of("-c", "git status")),
                null);
        PolicyDecision wrapper = misconfigured.evaluate(
                new CommandInvocation("/usr/bin/env", List.of("bash", "-c", "git status")),
                null);

        assertEquals("command.shell-denied", shell.reasonCode());
        assertEquals("command.shell-denied", wrapper.reasonCode());
        assertFalse(shell.resumable());
    }

    @Test
    void approvalIsBoundToExactCommandRunProjectRiskAndExpiry() {
        CommandInvocation push = new CommandInvocation(GIT, List.of("push", "origin"));
        CommandInvocation delete = new CommandInvocation(DELETE, List.of("file.txt"));
        CommandApprovalGrant exact = grant(push, CommandRisk.EXTERNAL_CHANGE, NOW.plusSeconds(30));
        CommandApprovalGrant wrongCommand = grant(delete, CommandRisk.DESTRUCTIVE, NOW.plusSeconds(30));
        CommandApprovalGrant expired = grant(push, CommandRisk.EXTERNAL_CHANGE, NOW);

        assertEquals("command.approval-required", policy.evaluate(push, null).reasonCode());
        assertTrue(policy.evaluate(push, null).resumable());
        assertEquals("command.approval-required", policy.evaluate(push, wrongCommand).reasonCode());
        assertEquals("command.approval-required", policy.evaluate(push, expired).reasonCode());
        assertEquals("command.approved", policy.evaluate(push, exact).reasonCode());
    }

    @Test
    void rejectsRelativeExecutablesAndDuplicateRulesAtConfigurationTime() {
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new CommandRule("git", List.of("status"), CommandRisk.READ_ONLY));
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new CommandPermissionPolicy(List.of(
                        new CommandRule(GIT, List.of("status"), CommandRisk.READ_ONLY),
                        new CommandRule(GIT, List.of("status"), CommandRisk.EXTERNAL_CHANGE))));
    }

    private static CommandApprovalGrant grant(
            CommandInvocation invocation,
            CommandRisk risk,
            Instant expiresAt) {
        return new CommandApprovalGrant(
                "run-001",
                PROJECT.toAbsolutePath().normalize().toString(),
                invocation.canonicalId(),
                risk,
                expiresAt.toEpochMilli(),
                "nonce-001");
    }
}
