package io.ohmyluke.tool;

import java.util.List;
import java.util.regex.Pattern;

/** Conservative output redaction applied before process text reaches state or an AI runtime. */
final class SecretRedactor {
    private static final List<Pattern> TOKEN_PATTERNS = List.of(
            Pattern.compile("gh[pousr]_[A-Za-z0-9_]{20,}"),
            Pattern.compile("sk-[A-Za-z0-9_-]{20,}"),
            Pattern.compile("eyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}"),
            Pattern.compile("(?i)(token|secret|password|api[_-]?key|authorization)\\s*[:=]\\s*[^\\s]+"));

    String redact(String value) {
        String redacted = value;
        for (Pattern pattern : TOKEN_PATTERNS) {
            redacted = pattern.matcher(redacted).replaceAll("[REDACTED]");
        }
        return redacted;
    }
}
