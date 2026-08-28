package io.ohmyluke.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class CommandPermissionPolicyTest {
    private final CommandPermissionPolicy policy = new CommandPermissionPolicy(List.of(
            new CommandRule("git", List.of("status"), CommandRisk.READ_ONLY),
            new CommandRule("git", List.of("diff"), CommandRisk.READ_ONLY),
            new CommandRule("git", List.of("add"), CommandRisk.PROJECT_WRITE),
            new CommandRule("git", List.of("push"), CommandRisk.EXTERNAL_CHANGE),
            new CommandRule("gradle", List.of("test"), CommandRisk.PROJECT_WRITE),
            new CommandRule("safe-delete", List.of(), CommandRisk.DESTRUCTIVE)));

    @Test
    void permitsOnlyAnAllowlistedExecutableAndArgumentPrefix() {
        PolicyDecision allowed = policy.evaluate(new CommandInvocation("git", List.of("status", "--short")), false);
        PolicyDecision wrongArguments = policy.evaluate(new CommandInvocation("git", List.of("reset", "--hard")), false);
        PolicyDecision unknown = policy.evaluate(new CommandInvocation("curl", List.of("https://example.com")), false);

        assertEquals(PolicyOutcome.CONTINUE, allowed.outcome());
        assertEquals("command.allowed", allowed.reasonCode());
        assertEquals("command.not-allowed", wrongArguments.reasonCode());
        assertEquals("command.not-allowed", unknown.reasonCode());
    }

    @Test
    void neverPermitsACommandShellEvenIfSomeoneAddsItToTheAllowlist() {
        CommandPermissionPolicy misconfigured = new CommandPermissionPolicy(List.of(
                new CommandRule("bash", List.of("-c"), CommandRisk.READ_ONLY)));

        PolicyDecision decision = misconfigured.evaluate(
                new CommandInvocation("bash", List.of("-c", "git status && curl example.com")),
                true);

        assertEquals(PolicyOutcome.BLOCKED, decision.outcome());
        assertEquals("command.shell-denied", decision.reasonCode());
        assertFalse(decision.resumable());
    }

    @Test
    void externalAndDestructiveEffectsRequireExplicitApproval() {
        PolicyDecision pushBlocked = policy.evaluate(new CommandInvocation("git", List.of("push", "origin")), false);
        PolicyDecision deleteBlocked = policy.evaluate(new CommandInvocation("safe-delete", List.of("file.txt")), false);
        PolicyDecision pushApproved = policy.evaluate(new CommandInvocation("git", List.of("push", "origin")), true);

        assertEquals("command.approval-required", pushBlocked.reasonCode());
        assertTrue(pushBlocked.resumable());
        assertEquals("command.approval-required", deleteBlocked.reasonCode());
        assertEquals(PolicyOutcome.CONTINUE, pushApproved.outcome());
        assertEquals("command.approved", pushApproved.reasonCode());
    }

    @Test
    void rejectsAmbiguousOrDuplicateRulesAtConfigurationTime() {
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new CommandPermissionPolicy(List.of(
                        new CommandRule("git", List.of("status"), CommandRisk.READ_ONLY),
                        new CommandRule("git", List.of("status"), CommandRisk.EXTERNAL_CHANGE))));
    }
}
