package io.ohmyluke.policy;

import java.util.List;
import java.util.Objects;

/** A command represented as executable and arguments, never as one shell string. */
public record CommandInvocation(String executable, List<String> arguments) {
    public CommandInvocation {
        executable = requireText(executable, "executable");
        arguments = List.copyOf(Objects.requireNonNull(arguments, "arguments"));
        arguments.forEach(argument -> {
            Objects.requireNonNull(argument, "command argument");
            if (argument.indexOf('\0') >= 0) {
                throw new IllegalArgumentException("command argument must not contain NUL");
            }
        });
    }

    public String display() {
        return arguments.isEmpty() ? executable : executable + " " + String.join(" ", arguments);
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(name + " must be non-blank and contain no NUL");
        }
        return value;
    }
}
