package io.ohmyluke.runtime;

import io.ohmyluke.graph.RunState;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Stable fingerprint of progress-relevant graph state, independent of map insertion order. */
final class RunStateFingerprint {
    private RunStateFingerprint() {}

    static String calculate(RunState state) {
        Objects.requireNonNull(state, "state");
        MessageDigest digest = sha256();
        update(digest, state.status().name());
        update(digest, state.currentNode().value());
        for (Map.Entry<String, String> entry : new TreeMap<>(state.values()).entrySet()) {
            update(digest, entry.getKey());
            update(digest, entry.getValue());
        }
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

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", impossible);
        }
    }
}
