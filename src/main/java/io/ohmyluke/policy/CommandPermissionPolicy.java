package io.ohmyluke.policy;

import java.nio.file.Path;
import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Validates structured commands against trusted rules without executing them. */
public final class CommandPermissionPolicy {
    private static final Set<String> COMMAND_SHELLS = Set.of(
            "sh", "bash", "zsh", "fish", "dash", "cmd", "cmd.exe", "powershell", "powershell.exe", "pwsh",
            "env", "busybox");

    private final List<CommandRule> rules;
    private final String runId;
    private final String projectRoot;
    private final Clock clock;

    public CommandPermissionPolicy(List<CommandRule> rules) {
        this(rules, "unscoped", Path.of(".").toAbsolutePath().normalize(), Clock.systemUTC());
    }

    public CommandPermissionPolicy(
            List<CommandRule> rules,
            String runId,
            Path projectRoot,
            Clock clock) {
        this.rules = List.copyOf(Objects.requireNonNull(rules, "rules"));
        this.runId = requireText(runId, "runId");
        this.projectRoot = Objects.requireNonNull(projectRoot, "projectRoot")
                .toAbsolutePath()
                .normalize()
                .toString();
        this.clock = Objects.requireNonNull(clock, "clock");
        Set<RuleKey> keys = new HashSet<>();
        for (CommandRule rule : this.rules) {
            Objects.requireNonNull(rule, "command rule");
            if (!keys.add(new RuleKey(rule.executable(), rule.arguments()))) {
                throw new IllegalArgumentException("duplicate command rule: " + rule.executable() + " " + rule.arguments());
            }
        }
    }

    public PolicyDecision evaluate(CommandInvocation invocation, CommandApprovalGrant approval) {
        Objects.requireNonNull(invocation, "invocation");
        if (isCommandShell(invocation.executable())) {
            return new PolicyDecision(
                    PolicyOutcome.BLOCKED,
                    "command.shell-denied",
                    "Command shells are denied; executable and arguments must remain structured",
                    false);
        }

        CommandRule matched = rules.stream()
                .filter(rule -> rule.matches(invocation))
                .findFirst()
                .orElse(null);
        if (matched == null) {
            return new PolicyDecision(
                    PolicyOutcome.BLOCKED,
                    "command.not-allowed",
                    "Executable and complete argument list are not on the trusted allowlist",
                    false);
        }

        if (matched.risk().requiresApproval() && !matchesApproval(approval, invocation, matched.risk())) {
            return new PolicyDecision(
                    PolicyOutcome.BLOCKED,
                    "command.approval-required",
                    "This command can change external state or delete data and requires explicit approval",
                    true);
        }

        String reason = matched.risk().requiresApproval() ? "command.approved" : "command.allowed";
        return PolicyDecision.continueExecution(reason, "Command matches a trusted allowlist rule");
    }

    private boolean matchesApproval(
            CommandApprovalGrant approval,
            CommandInvocation invocation,
            CommandRisk risk) {
        return approval != null
                && approval.runId().equals(runId)
                && approval.projectRoot().equals(projectRoot)
                && approval.invocationId().equals(invocation.canonicalId())
                && approval.risk() == risk
                && approval.expiresAtEpochMilli() > clock.millis();
    }

    private static boolean isCommandShell(String executable) {
        String fileName;
        try {
            Path path = Path.of(executable);
            Path name = path.getFileName();
            fileName = name == null ? executable : name.toString();
        } catch (RuntimeException exception) {
            fileName = executable;
        }
        return COMMAND_SHELLS.contains(fileName.toLowerCase(Locale.ROOT));
    }

    private record RuleKey(String executable, List<String> arguments) {}

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
