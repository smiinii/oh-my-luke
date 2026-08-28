package io.ohmyluke.state;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** Renders and atomically stores the human-readable run handoff note. */
public final class HandoffStore {
    private static final String HANDOFF_FILE = "handoff.md";

    private final Path projectRoot;

    public HandoffStore(Path projectRoot) {
        this.projectRoot = RunFileSupport.normalizeRoot(projectRoot);
    }

    public void save(String runId, HandoffNote note) {
        Objects.requireNonNull(note, "note");
        RunFileSupport.writeAtomically(handoffPath(runId), render(note));
    }

    public Path handoffPath(String runId) {
        return RunFileSupport.file(projectRoot, runId, HANDOFF_FILE);
    }

    static String render(HandoffNote note) {
        StringBuilder markdown = new StringBuilder("# Handoff\n\n");
        markdown.append("- 목표: ").append(note.goal()).append('\n');
        appendList(markdown, "지금까지 확인한 사실", note.confirmedFacts(), false);
        appendList(markdown, "변경한 파일", note.changedFiles(), true);
        appendList(markdown, "남은 실패", note.remainingFailures(), true);
        appendList(markdown, "하지 말아야 할 시도", note.forbiddenAttempts(), false);
        markdown.append("- 다음 행동: ").append(note.nextAction()).append('\n');
        return markdown.toString();
    }

    private static void appendList(
            StringBuilder markdown,
            String label,
            List<String> values,
            boolean code) {
        markdown.append("- ").append(label).append(":\n");
        if (values.isEmpty()) {
            markdown.append("  - 없음\n");
            return;
        }
        for (String value : values) {
            markdown.append("  - ");
            if (code) {
                markdown.append('`').append(value).append('`');
            } else {
                markdown.append(value);
            }
            markdown.append('\n');
        }
    }
}
