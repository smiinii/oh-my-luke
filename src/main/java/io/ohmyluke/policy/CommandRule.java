package io.ohmyluke.policy;

import java.util.List;
import java.util.Objects;

/** An exact executable and argument-prefix allowlist entry configured by the operator. */
public record CommandRule(String executable, List<String> argumentPrefix, CommandRisk risk) {
    public CommandRule {
        executable = requireText(executable, "executable");
        argumentPrefix = List.copyOf(Objects.requireNonNull(argumentPrefix, "argumentPrefix"));
        argumentPrefix.forEach(argument -> requireText(argument, "argumentPrefix entry"));
        Objects.requireNonNull(risk, "risk");
    }

    public boolean matches(CommandInvocation invocation) {
        if (!executable.equals(invocation.executable())
                || argumentPrefix.size() > invocation.arguments().size()) {
            return false;
        }
        return invocation.arguments().subList(0, argumentPrefix.size()).equals(argumentPrefix);
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(name + " must be non-blank and contain no NUL");
        }
        return value;
    }
}
