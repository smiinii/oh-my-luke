package io.ohmyluke.policy;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Validates structured commands against trusted rules without executing them. */
public final class CommandPermissionPolicy {
    private static final Set<String> COMMAND_SHELLS = Set.of(
            "sh", "bash", "zsh", "fish", "dash", "cmd", "cmd.exe", "powershell", "powershell.exe", "pwsh");

    private final List<CommandRule> rules;

    public CommandPermissionPolicy(List<CommandRule> rules) {
        this.rules = List.copyOf(Objects.requireNonNull(rules, "rules"));
        Set<RuleKey> keys = new HashSet<>();
        for (CommandRule rule : this.rules) {
            Objects.requireNonNull(rule, "command rule");
            if (!keys.add(new RuleKey(rule.executable(), rule.argumentPrefix()))) {
                throw new IllegalArgumentException("duplicate command rule: " + rule.executable() + " " + rule.argumentPrefix());
            }
        }
    }

    public PolicyDecision evaluate(CommandInvocation invocation, boolean explicitlyApproved) {
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
                .max(java.util.Comparator.comparingInt(rule -> rule.argumentPrefix().size()))
                .orElse(null);
        if (matched == null) {
            return new PolicyDecision(
                    PolicyOutcome.BLOCKED,
                    "command.not-allowed",
                    "Executable and argument prefix are not on the trusted allowlist",
                    false);
        }

        if (matched.risk().requiresApproval() && !explicitlyApproved) {
            return new PolicyDecision(
                    PolicyOutcome.BLOCKED,
                    "command.approval-required",
                    "This command can change external state or delete data and requires explicit approval",
                    true);
        }

        String reason = matched.risk().requiresApproval() ? "command.approved" : "command.allowed";
        return PolicyDecision.continueExecution(reason, "Command matches a trusted allowlist rule");
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

    private record RuleKey(String executable, List<String> argumentPrefix) {}
}
