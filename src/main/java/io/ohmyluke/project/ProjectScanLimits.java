package io.ohmyluke.project;

public record ProjectScanLimits(
        int maxEntries,
        long maxIncludedBytes,
        long maxFileBytes,
        int maxDepth) {
    private static final int DEFAULT_MAX_ENTRIES = 20_000;
    private static final long DEFAULT_MAX_INCLUDED_BYTES = 64L * 1024 * 1024;
    private static final long DEFAULT_MAX_FILE_BYTES = 2L * 1024 * 1024;
    private static final int DEFAULT_MAX_DEPTH = 32;

    public ProjectScanLimits {
        if (maxEntries <= 0) {
            throw new IllegalArgumentException("maxEntries must be positive");
        }
        if (maxIncludedBytes <= 0) {
            throw new IllegalArgumentException("maxIncludedBytes must be positive");
        }
        if (maxFileBytes <= 0 || maxFileBytes > maxIncludedBytes) {
            throw new IllegalArgumentException("maxFileBytes must be positive and not exceed maxIncludedBytes");
        }
        if (maxDepth < 0 || maxDepth > 256) {
            throw new IllegalArgumentException("maxDepth must be between 0 and 256");
        }
    }

    public static ProjectScanLimits defaults() {
        return new ProjectScanLimits(
                DEFAULT_MAX_ENTRIES,
                DEFAULT_MAX_INCLUDED_BYTES,
                DEFAULT_MAX_FILE_BYTES,
                DEFAULT_MAX_DEPTH);
    }
}
