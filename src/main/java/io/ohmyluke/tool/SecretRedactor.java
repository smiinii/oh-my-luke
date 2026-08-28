package io.ohmyluke.tool;

import java.util.List;
import java.util.regex.Pattern;

/** Conservative output redaction applied before process text reaches state or an AI runtime. */
final class SecretRedactor {
    private static final List<Pattern> TOKEN_PATTERNS = List.of(
            Pattern.compile("gh[pousr]_[A-Za-z0-9_]{20,}"),
            Pattern.compile("sk-[A-Za-z0-9_-]{20,}"),
            Pattern.compile("AKIA[0-9A-Z]{16}"),
            Pattern.compile("eyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}"),
            Pattern.compile("(?i)(?:proxy-)?authorization\\s*[:=][^\\r\\n]*"),
            Pattern.compile("(?i)set-cookie\\s*:[^\\r\\n]*"),
            Pattern.compile("(?i)(token|secret|password|api[_-]?key)\\s*[:=]\\s*[^\\s]+"));
    private static final Pattern TRUNCATED_SECRET_SUFFIX = Pattern.compile(
            "(?i)(?:gh[pousr]_[A-Za-z0-9_]*|sk-[A-Za-z0-9_-]*|AKIA[0-9A-Z]*|eyJ[A-Za-z0-9_.-]*|(?:(?:proxy-)?authorization|set-cookie|token|secret|password|api[_-]?key)\\s*[:=].*)$");

    String redact(String value, boolean truncated) {
        String redacted = value;
        for (Pattern pattern : TOKEN_PATTERNS) {
            redacted = pattern.matcher(redacted).replaceAll("[REDACTED]");
        }
        if (truncated) {
            redacted = TRUNCATED_SECRET_SUFFIX.matcher(redacted).replaceAll("[REDACTED]");
        }
        return redacted;
    }
}
