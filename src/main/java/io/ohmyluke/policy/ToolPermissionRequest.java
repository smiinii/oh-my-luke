package io.ohmyluke.policy;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/** Model-independent description of the capability and exact target a tool wants to use. */
public record ToolPermissionRequest(
        String operationId,
        String runId,
        String projectRoot,
        ToolCapability capability,
        String target) {
    private static final Pattern CREDENTIAL_PARAMETER = Pattern.compile(
            "(?i)(?:^|[?&;,\\s])(?:api[_-]?key|token|secret|password|passwd|authorization|auth|cookie|credential)\\s*=");
    public ToolPermissionRequest(
            String operationId,
            String runId,
            Path projectRoot,
            ToolCapability capability,
            String target) {
        this(
                operationId,
                runId,
                normalizeProjectRoot(projectRoot),
                capability,
                target);
    }

    public ToolPermissionRequest {
        operationId = requireText(operationId, "operationId");
        runId = requireText(runId, "runId");
        projectRoot = normalizeProjectRoot(Path.of(requireText(projectRoot, "projectRoot")));
        Objects.requireNonNull(capability, "capability");
        target = requireText(target, "target");
        rejectCredentials(target);
    }

    private static String normalizeProjectRoot(Path projectRoot) {
        Path normalized = Objects.requireNonNull(projectRoot, "projectRoot")
                .toAbsolutePath()
                .normalize();
        try {
            return normalized.toRealPath().toString();
        } catch (java.io.IOException error) {
            throw new IllegalArgumentException("projectRoot must exist and resolve safely", error);
        }
    }

    private static void rejectCredentials(String target) {
        if (CREDENTIAL_PARAMETER.matcher(target).find()) {
            throw new IllegalArgumentException("target must not contain credential parameters");
        }
        String lower = target.toLowerCase(Locale.ROOT);
        int scheme = lower.indexOf("://");
        if (scheme >= 0) {
            int authorityStart = scheme + 3;
            int authorityEnd = target.length();
            for (char delimiter : new char[] {'/', '?', '#'}) {
                int index = target.indexOf(delimiter, authorityStart);
                if (index >= 0) {
                    authorityEnd = Math.min(authorityEnd, index);
                }
            }
            if (target.substring(authorityStart, authorityEnd).contains("@")) {
                throw new IllegalArgumentException("target must not contain URL user-info");
            }
        }
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(name + " must be non-blank and contain no NUL");
        }
        return value;
    }
}
