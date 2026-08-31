package io.ohmyluke.tool;

import java.util.List;
import java.util.regex.Pattern;

/** Conservative output redaction applied before process text reaches state or an AI runtime. */
public final class SecretRedactor {
    private static final String JSON_SECRET_KEY =
            "(?:proxy-)?authorization|(?:set-)?cookie|token|secret|password|api[_-]?key";
    private static final List<Pattern> TOKEN_PATTERNS = List.of(
            Pattern.compile("gh[pousr]_[A-Za-z0-9_]{20,}"),
            Pattern.compile("sk-[A-Za-z0-9_-]{20,}"),
            Pattern.compile("AKIA[0-9A-Z]{16}"),
            Pattern.compile("eyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}"),
            Pattern.compile("(?i)\\\"(?:" + JSON_SECRET_KEY
                    + ")\\\"\\s*:\\s*(?:\\\"(?:[^\\\"\\\\\\r\\n]|\\\\.)*\\\"|[^,}\\r\\n]+)"),
            Pattern.compile("(?i)'(?:" + JSON_SECRET_KEY
                    + ")'\\s*:\\s*(?:'(?:[^'\\\\\\r\\n]|\\\\.)*'|[^,}\\r\\n]+)"),
            Pattern.compile("(?i)(?:proxy-)?authorization\\s*[:=][^\\r\\n]*"),
            Pattern.compile("(?i)(?<![A-Za-z0-9_-])(?:set-)?cookie\\s*[:=][^\\r\\n]*"),
            Pattern.compile("(?i)(token|secret|password|api[_-]?key)\\s*[:=]\\s*[^\\s]+"));
    private static final List<Pattern> TRUNCATED_SECRET_SUFFIXES = List.of(
            Pattern.compile("(?i)\\\"(?:" + JSON_SECRET_KEY
                    + ")\\\"\\s*:\\s*(?:\\\"(?:[^\\\"\\\\\\r\\n]|\\\\.)*|[^,}\\r\\n]*)$"),
            Pattern.compile("(?i)'(?:" + JSON_SECRET_KEY
                    + ")'\\s*:\\s*(?:'(?:[^'\\\\\\r\\n]|\\\\.)*|[^,}\\r\\n]*)$"),
            Pattern.compile(
                    "(?i)(?:gh[pousr]_[A-Za-z0-9_]*|sk-[A-Za-z0-9_-]*|AKIA[0-9A-Z]*|eyJ[A-Za-z0-9_.-]*|(?<![A-Za-z0-9_-])(?:set-)?cookie\\s*[:=].*|(?:(?:proxy-)?authorization|token|secret|password|api[_-]?key)\\s*[:=].*)$"));

    public String redact(String value, boolean truncated) {
        String redacted = value;
        for (Pattern pattern : TOKEN_PATTERNS) {
            redacted = pattern.matcher(redacted).replaceAll("[REDACTED]");
        }
        if (truncated) {
            for (Pattern pattern : TRUNCATED_SECRET_SUFFIXES) {
                redacted = pattern.matcher(redacted).replaceAll("[REDACTED]");
            }
        }
        return redacted;
    }
}
