package io.ohmyluke.preset;

import io.ohmyluke.tool.ToolArtifactStore;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Content-addressed small snapshots: graph checkpoints retain hashes, not repeated file bodies. */
final class PresetContentStore {
    static final int MAX_BYTES = 64 * 1024;
    private final ToolArtifactStore artifacts;

    PresetContentStore(Path project, String runId) { artifacts = new ToolArtifactStore(project, runId); }

    String save(byte[] bytes) {
        text(bytes);
        String hash = hash(bytes);
        artifacts.store("preset-content", hash + ".txt", bytes);
        return hash;
    }

    byte[] read(String hash) {
        if (hash == null || !hash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("invalid preset content reference");
        }
        byte[] bytes = artifacts.read("preset-content", hash + ".txt", MAX_BYTES);
        if (!hash(bytes).equals(hash)) { throw new IllegalStateException("preset content integrity mismatch"); }
        text(bytes);
        return bytes;
    }

    static String text(byte[] bytes) {
        if (bytes.length > MAX_BYTES) { throw new IllegalArgumentException("task file exceeds 64 KiB"); }
        try {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            if (text.indexOf('\0') >= 0) { throw new IllegalArgumentException("binary task file"); }
            TaskSpec.rejectSecrets(text);
            return text;
        } catch (CharacterCodingException error) {
            throw new IllegalArgumentException("task file must be UTF-8");
        }
    }

    static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
