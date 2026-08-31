package io.ohmyluke.preset;

import java.nio.charset.StandardCharsets;

/** Whole-file replacement of the one explicitly allowed file. No commands or policy fields. */
public record EditProposal(String path, String content) {
    public EditProposal {
        path = TaskSpec.relativeFile(path);
        if (content == null || content.indexOf('\0') >= 0
                || content.getBytes(StandardCharsets.UTF_8).length > PresetContentStore.MAX_BYTES) {
            throw new IllegalArgumentException("invalid proposal content");
        }
        TaskSpec.rejectSecrets(content);
    }
}
