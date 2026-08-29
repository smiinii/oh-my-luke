package io.ohmyluke.policy;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class SensitivePathPolicyTest {
    @Test
    void environmentTemplatesAreAllowedOnlyOutsideSensitiveDirectories() {
        assertFalse(SensitivePathPolicy.isSensitive(Path.of(".env.example")));
        assertFalse(SensitivePathPolicy.isSensitive(Path.of("config/.env.template")));

        assertTrue(SensitivePathPolicy.isSensitive(Path.of(".ssh/.env.example")));
        assertTrue(SensitivePathPolicy.isSensitive(Path.of("project/.aws/.env.template")));
        assertTrue(SensitivePathPolicy.isSensitive(Path.of("project/.config/gh/.env.example")));
    }
}
