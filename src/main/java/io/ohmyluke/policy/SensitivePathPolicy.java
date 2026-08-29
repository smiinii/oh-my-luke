package io.ohmyluke.policy;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Shared deny policy for paths that may contain credentials or private key material. */
public final class SensitivePathPolicy {
    private static final Set<String> SECRET_NAMES = Set.of(
            ".env",
            ".npmrc",
            ".netrc",
            ".pypirc",
            "settings.xml",
            "credentials",
            "credentials.json",
            "service-account.json",
            "docker-config.json",
            "id_rsa",
            "id_ed25519",
            "hosts.yml");
    private static final List<String> SECRET_SUFFIXES = List.of(".pem", ".key", ".p12", ".pfx");

    private SensitivePathPolicy() {}

    public static boolean isSensitive(Path path) {
        String normalized = path.toString().replace('\\', '/').toLowerCase(Locale.ROOT);
        String fileName = path.getFileName() == null
                ? ""
                : path.getFileName().toString().toLowerCase(Locale.ROOT);
        boolean sensitiveDirectory = containsDirectory(normalized, ".ssh")
                || containsDirectory(normalized, ".aws")
                || containsDirectorySequence(normalized, ".config/gh")
                || containsDirectorySequence(normalized, ".config/codex")
                || containsDirectorySequence(normalized, ".config/openai")
                || containsDirectory(normalized, ".codex")
                || containsDirectory(normalized, ".claude")
                || containsDirectory(normalized, ".azure")
                || containsDirectorySequence(normalized, ".config/gcloud");
        boolean environmentTemplate = fileName.equals(".env.example")
                || fileName.equals(".env.template");
        return sensitiveDirectory
                || (!environmentTemplate && SECRET_NAMES.contains(fileName))
                || (!environmentTemplate && fileName.startsWith(".env."))
                || SECRET_SUFFIXES.stream().anyMatch(fileName::endsWith);
    }

    private static boolean containsDirectory(String normalized, String directory) {
        return normalized.equals(directory)
                || normalized.startsWith(directory + "/")
                || normalized.endsWith("/" + directory)
                || normalized.contains("/" + directory + "/");
    }

    private static boolean containsDirectorySequence(String normalized, String sequence) {
        return normalized.equals(sequence)
                || normalized.startsWith(sequence + "/")
                || normalized.endsWith("/" + sequence)
                || normalized.contains("/" + sequence + "/");
    }
}
