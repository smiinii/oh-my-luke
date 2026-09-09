package io.ohmyluke.profile;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExecutionProfilesTest {
    @TempDir Path directory;

    @Test void defaultsAndReadOnlyResolutionNeverCreateFiles() throws Exception {
        Path home = Files.createDirectory(directory.resolve("home"));
        Path project = Files.createDirectory(directory.resolve("project"));
        var profiles = new ExecutionProfiles(home, project);
        assertEquals(ExecutionProfile.defaults(), profiles.resolve().profile());
        assertEquals("builtin", profiles.resolve().source());
        assertFalse(Files.exists(home.resolve(".oml")));
        assertFalse(Files.exists(project.resolve(".oml")));
    }

    @Test void setupPreservesExistingSettingsAndProjectResetPreservesRecords() throws Exception {
        Path home = Files.createDirectory(directory.resolve("home"));
        Path project = Files.createDirectory(directory.resolve("project"));
        var profiles = new ExecutionProfiles(home, project);
        assertTrue(profiles.setup());
        profiles.saveGlobal(new ExecutionProfile("codex", "oml", "model-a"));
        String original = Files.readString(home.resolve(".oml/settings.json"));
        assertFalse(profiles.setup());
        assertEquals(original, Files.readString(home.resolve(".oml/settings.json")));
        assertEquals("global", profiles.resolve().source());
        profiles.saveProject(new ExecutionProfile("codex", "oml", "model-b"));
        profiles.saveGlobal(new ExecutionProfile("codex", "oml", "model-c"));
        assertEquals("model-b", profiles.resolve().profile().model());
        Path record = project.resolve(".oml/runs/retained/state.json");
        Files.createDirectories(record.getParent());
        Files.writeString(record, "keep");
        assertTrue(profiles.resetProject());
        assertFalse(profiles.resetProject());
        assertEquals("model-c", profiles.resolve().profile().model());
        assertEquals("keep", Files.readString(record));
        profiles.saveProject(ExecutionProfile.defaults());
        assertNull(profiles.resolve().profile().model(), "explicit CLI inheritance must not inherit global model");
    }

    @Test void copiedProjectSettingsFailClosedAndDoNotExposeTheirContents() throws Exception {
        Path home = Files.createDirectory(directory.resolve("home"));
        Path a = Files.createDirectory(directory.resolve("a"));
        Path b = Files.createDirectory(directory.resolve("b"));
        new ExecutionProfiles(home, a).saveProject(ExecutionProfile.defaults());
        Files.createDirectories(b.resolve(".oml"));
        Files.copy(a.resolve(".oml/profile.json"), b.resolve(".oml/profile.json"));
        assertThrows(IllegalStateException.class, () -> new ExecutionProfiles(home, b).resolve());
        assertThrows(IllegalStateException.class, () -> new ExecutionProfiles(home, b).resetProject());
    }

    @Test void invalidSettingsNeverFallBackOrGetOverwrittenBySetupOrSwitch() throws Exception {
        Path home = Files.createDirectory(directory.resolve("home"));
        Path config = Files.createDirectories(home.resolve(".oml")).resolve("settings.json");
        var profiles = new ExecutionProfiles(home, null);
        for (String invalid : List.of("", "null", "{}", "[]", "{", "x".repeat(17_000),
                "{\"schemaVersion\":1,\"schemaVersion\":1}",
                "{\"schemaVersion\":2,\"projectRoot\":null,\"profile\":{\"runtime\":\"codex\",\"harness\":\"oml\",\"model\":null}}",
                "{\"schemaVersion\":1,\"projectRoot\":null,\"profile\":{\"runtime\":\"codex\",\"harness\":\"omx\",\"model\":null}}")) {
            Files.writeString(config, invalid);
            assertThrows(IllegalStateException.class, profiles::resolve);
            assertThrows(IllegalStateException.class, profiles::setup);
            assertThrows(IllegalStateException.class, () -> profiles.saveGlobal(ExecutionProfile.defaults()));
            assertEquals(invalid, Files.readString(config));
        }
    }

    @Test void concurrentInitializationHasExactlyOneCreator() throws Exception {
        Path home = Files.createDirectory(directory.resolve("home"));
        try (var pool = Executors.newFixedThreadPool(8)) {
            var futures = pool.invokeAll(java.util.stream.IntStream.range(0, 16)
                    .<java.util.concurrent.Callable<Boolean>>mapToObj(i -> () -> new ExecutionProfiles(home, null).setup()).toList());
            int created = 0;
            for (var result : futures) { if (result.get()) { created++; } }
            assertEquals(1, created);
        }
        assertEquals(ExecutionProfile.defaults(), new ExecutionProfiles(home, null).resolve().profile());
    }

    @Test void strictSchemaRejectsCoercionUnknownFieldsAndTrailingDocuments() throws Exception {
        Path home = Files.createDirectory(directory.resolve("home"));
        var profiles = new ExecutionProfiles(home, null);
        profiles.setup();
        Path config = home.resolve(".oml/settings.json");
        String valid = Files.readString(config);
        for (String invalid : List.of(valid.replace("\"schemaVersion\":1", "\"schemaVersion\":1.1"),
                valid.replace("\"schemaVersion\":1", "\"schemaVersion\":\"1\""),
                valid.replace("\"model\":null", "\"model\":123"),
                valid.replace("\"model\":null", "\"model\":false"),
                valid.replace("\"model\":null", "\"unexpected\":null"), valid + " {}")) {
            Files.writeString(config, invalid);
            var failure = assertThrows(IllegalStateException.class, profiles::resolve);
            assertFalse(failure.getMessage().contains(invalid));
        }
    }

    @Test void rejectsUnsupportedCombinationsAndUnsafeModelText() {
        assertThrows(IllegalArgumentException.class, () -> new ExecutionProfile("claude", "oml", null));
        assertThrows(IllegalArgumentException.class, () -> new ExecutionProfile("codex", "omx", null));
        for (String value : List.of("", "bad\nmodel", "--help", "x".repeat(129), "$(whoami)")) {
            assertThrows(IllegalArgumentException.class, () -> new ExecutionProfile("codex", "oml", value));
        }
    }
}
