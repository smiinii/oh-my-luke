package io.ohmyluke.project;

import java.util.List;
import java.util.Objects;

public record ProjectScanSummary(
        int visitedEntries,
        int includedFiles,
        int excludedEntries,
        long includedBytes,
        boolean truncated,
        List<ProjectScanNotice> notices) {
    public ProjectScanSummary {
        if (visitedEntries < 0 || includedFiles < 0 || excludedEntries < 0 || includedBytes < 0) {
            throw new IllegalArgumentException("scan counters must not be negative");
        }
        notices = List.copyOf(Objects.requireNonNull(notices, "notices"));
    }
}
