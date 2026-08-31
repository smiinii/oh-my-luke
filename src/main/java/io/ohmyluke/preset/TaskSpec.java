package io.ohmyluke.preset;

import io.ohmyluke.ai.codex.CodexModelSelection;
import io.ohmyluke.ai.codex.CodexReasoningEffort;
import io.ohmyluke.policy.SensitivePathPolicy;
import io.ohmyluke.tool.SecretRedactor;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;

/** Operator-owned immutable task contract; proposals cannot change it. */
public record TaskSpec(int schemaVersion, String goal, String file, ExecutionMode mode,
                       int maxAttempts, long maxUsage, long maxElapsedMillis, int maxRepeatedFailures,
                       ValidationSpec validation, String model, String reasoning) {
    public TaskSpec {
        if (schemaVersion != 1) { throw new IllegalArgumentException("unsupported task schema version"); }
        goal = text(goal, 8_192, "goal");
        file = relativeFile(file);
        Objects.requireNonNull(mode, "mode");
        if (maxAttempts < 1 || maxAttempts > 20 || (mode == ExecutionMode.DIRECT && maxAttempts != 1)) {
            throw new IllegalArgumentException("maxAttempts must be 1..20; DIRECT requires 1");
        }
        if (maxUsage < 0 || maxElapsedMillis < 1 || maxElapsedMillis > 3_600_000
                || maxRepeatedFailures < 1 || maxRepeatedFailures > 20) {
            throw new IllegalArgumentException("invalid preset limits");
        }
        Objects.requireNonNull(validation, "validation");
        if (model != null) { rejectSecrets(model); CodexModelSelection.explicit(model); }
        if (reasoning != null) {
            reasoning = reasoning.toLowerCase(Locale.ROOT);
            try { CodexReasoningEffort.valueOf(reasoning.toUpperCase(Locale.ROOT)); }
            catch (IllegalArgumentException error) { throw new IllegalArgumentException("unsupported reasoning level"); }
        }
    }

    public TaskSpec withRuntimeSelection(String modelOverride, String reasoningOverride) {
        return new TaskSpec(schemaVersion, goal, file, mode, maxAttempts, maxUsage, maxElapsedMillis,
                maxRepeatedFailures, validation, modelOverride == null ? model : modelOverride,
                reasoningOverride == null ? reasoning : reasoningOverride);
    }

    static String text(String value, int max, String name) {
        if (value == null || value.isBlank() || value.length() > max || value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("invalid " + name);
        }
        rejectSecrets(value);
        return value;
    }

    static void rejectSecrets(String value) {
        if (!new SecretRedactor().redact(value, false).equals(value)) {
            throw new IllegalArgumentException("potential secret in preset input");
        }
    }

    static String relativeFile(String value) {
        text(value, 512, "file");
        Path path = Path.of(value);
        if (path.isAbsolute() || value.contains("\\") || value.contains(":")
                || !path.normalize().toString().replace('\\', '/').equals(value) || value.equals(".")
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("file must be a normalized project-relative path");
        }
        for (Path part : path) {
            if (part.toString().equals("..") || part.toString().equalsIgnoreCase(".oml")
                    || part.toString().equalsIgnoreCase(".git") || SensitivePathPolicy.isSensitive(part)) {
                throw new IllegalArgumentException("protected task file");
            }
        }
        return value;
    }
}
