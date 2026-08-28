package io.ohmyluke.policy;

import java.util.List;
import java.util.Objects;

/** An exact executable and complete argument-list allowlist entry configured by the operator. */
public record CommandRule(String executable, List<String> arguments, CommandRisk risk) {
    public CommandRule {
        executable = requireText(executable, "executable");
        if (!java.nio.file.Path.of(executable).isAbsolute()) {
            throw new IllegalArgumentException("executable must be an operator-trusted absolute path");
        }
        arguments = List.copyOf(Objects.requireNonNull(arguments, "arguments"));
        arguments.forEach(argument -> requireText(argument, "arguments entry"));
        Objects.requireNonNull(risk, "risk");
    }

    public boolean matches(CommandInvocation invocation) {
        return executable.equals(invocation.executable())
                && arguments.equals(invocation.arguments());
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(name + " must be non-blank and contain no NUL");
        }
        return value;
    }
}
