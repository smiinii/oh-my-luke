package io.ohmyluke.policy;

import java.util.List;
import java.util.Objects;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

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

    /** Collision-resistant identity that preserves executable and argument boundaries. */
    public String canonicalId() {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", impossible);
        }
        update(digest, executable);
        arguments.forEach(argument -> update(digest, argument));
        return java.util.HexFormat.of().formatHex(digest.digest());
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update((byte) (bytes.length >>> 24));
        digest.update((byte) (bytes.length >>> 16));
        digest.update((byte) (bytes.length >>> 8));
        digest.update((byte) bytes.length);
        digest.update(bytes);
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(name + " must be non-blank and contain no NUL");
        }
        return value;
    }
}
