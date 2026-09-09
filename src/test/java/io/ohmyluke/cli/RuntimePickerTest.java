package io.ohmyluke.cli;

import static org.junit.jupiter.api.Assertions.*;
import io.ohmyluke.ai.codex.CodexModelCatalog;
import io.ohmyluke.profile.*;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RuntimePickerTest {
    @TempDir Path directory;
    private final ByteArrayOutputStream output = new ByteArrayOutputStream();
    private final PrintStream out = new PrintStream(output);

    @Test void setupAndSwitchUseTheSameScreenAndCommitOnlyAfterFinalConfirmation() throws Exception {
        Path home = Files.createDirectory(directory.resolve("home"));
        var profiles = new ExecutionProfiles(home, directory);
        var choices = new ArrayDeque<>(List.of("1", "1", "2", "1"));
        AtomicInteger reads = new AtomicInteger();
        var cli = cli(profiles, () -> {
            assertFalse(Files.exists(home.resolve(".oml/settings.json")));
            reads.incrementAndGet(); return choices.poll();
        });
        assertEquals(0, cli.execute(new String[] {"setup"}));
        assertEquals(4, reads.get());
        assertEquals("model-a", profiles.resolve().profile().model());
        var switched = cli(profiles, inputs("1", "1", "1", "2", "1"));
        assertEquals(0, switched.execute(new String[] {"switch"}));
        assertEquals("project", profiles.resolve().source());
        assertNull(profiles.resolve().profile().model());
        assertEquals("model-a", profiles.selection(false).model());
        assertTrue(output.toString().contains("1/4  실행기"));
        assertTrue(output.toString().contains("2/4  하네스"));
        assertTrue(output.toString().contains("연결 준비 중"));
    }

    @Test void cancellationAndEofAtEveryStageDoNotCreateAnySettings() throws Exception {
        Path home = Files.createDirectory(directory.resolve("home"));
        var profiles = new ExecutionProfiles(home, directory);
        for (int stage = 0; stage < 4; stage++) {
            var choices = new ArrayDeque<String>();
            for (int i = 0; i < stage; i++) { choices.add("1"); }
            assertEquals(130, cli(profiles, choices::poll).execute(new String[] {"setup"}));
            choices.add("0");
            assertEquals(130, cli(profiles, choices::poll).execute(new String[] {"setup"}));
            assertFalse(Files.exists(home.resolve(".oml")));
            assertFalse(Files.exists(directory.resolve(".oml")));
        }
    }

    @Test void backNavigationDoesNotRefetchModelsAndExistingUnavailableSelectionCanBeKept() throws Exception {
        Path home = Files.createDirectory(directory.resolve("home"));
        var profiles = new ExecutionProfiles(home, directory);
        profiles.saveGlobal(new ExecutionProfile("codex", "oml", "old-model"));
        AtomicInteger loads = new AtomicInteger();
        var picker = new RuntimePicker(inputs("1", "1", "b", "1", "2", "1", "b", "1", "1"), out);
        var cli = new ProfileCli(profiles, directory, out, picker, this::tools, executable -> {
            loads.incrementAndGet(); return CodexModelCatalog.unavailable();
        });
        assertEquals(0, cli.execute(new String[] {"switch"}));
        assertEquals(1, loads.get());
        assertEquals("old-model", profiles.resolve().profile().model());
        assertTrue(output.toString().contains("목록 확인 안 됨"));
    }

    @Test void concurrentChangeWinsInsteadOfBeingOverwrittenByAnOldWizard() throws Exception {
        Path home = Files.createDirectory(directory.resolve("home"));
        var profiles = new ExecutionProfiles(home, directory);
        AtomicInteger count = new AtomicInteger();
        var cli = cli(profiles, () -> {
            if (count.incrementAndGet() == 4) { profiles.saveGlobal(new ExecutionProfile("codex", "oml", "changed-elsewhere")); }
            return "1";
        });
        assertThrows(IllegalStateException.class, () -> cli.execute(new String[] {"setup"}));
        assertEquals("changed-elsewhere", profiles.resolve().profile().model());
    }

    @Test void nonInteractiveSwitchNeverReadsInputQueriesModelsOrChangesSettings() throws Exception {
        Path home = Files.createDirectory(directory.resolve("home"));
        var profiles = new ExecutionProfiles(home, null);
        var cli = new ProfileCli(profiles, null, out, new RuntimePicker(null, out),
                () -> { throw new AssertionError("no discovery"); }, path -> { throw new AssertionError("no query"); });
        assertEquals(2, cli.execute(new String[] {"switch"}));
        assertFalse(Files.exists(home.resolve(".oml")));
        assertEquals(0, cli.execute(new String[] {"setup", "--defaults"}));
    }

    @Test void homeScopeCannotAccidentallyOfferAProjectAndModelKeysCannotActAsMenuCommands() {
        var picker = new RuntimePicker(inputs("1", "1", "2", "1"), out);
        var catalog = new CodexModelCatalog(CodexModelCatalog.Status.AVAILABLE,
                List.of(new CodexModelCatalog.Model("inherit", "Model called inherit", false)));
        var result = picker.choose(false, null, ExecutionProfile.defaults(), ExecutionProfile.defaults(), tools(), path -> catalog).orElseThrow();
        assertEquals("global", result.scope());
        assertEquals("inherit", result.profile().model());
        assertFalse(output.toString().contains("이 작업 폴더만 고정"));
    }

    @Test void paginationAndInvalidInputAreBoundedWithoutImplicitDefaults() {
        var catalog = new CodexModelCatalog(CodexModelCatalog.Status.AVAILABLE,
                java.util.stream.IntStream.range(0, 12).mapToObj(i -> new CodexModelCatalog.Model("m" + i, "model" + i, false)).toList());
        var picker = new RuntimePicker(inputs("1", "1", "n", "9", "1"), out);
        var result = picker.choose(false, null, ExecutionProfile.defaults(), ExecutionProfile.defaults(), tools(), path -> catalog).orElseThrow();
        assertEquals("m7", result.profile().model());
        assertThrows(IllegalArgumentException.class, () -> new RuntimePicker(inputs("", "wrong", "99"), out)
                .choose(true, null, ExecutionProfile.defaults(), ExecutionProfile.defaults(), tools(), path -> catalog));
    }

    @Test void backFromSetupConfirmationReturnsToModelsInsteadOfLoopingOverAnAutomaticScope() {
        var picker = new RuntimePicker(inputs("1", "1", "2", "b", "1", "1"), out);
        var result = picker.choose(true, null, ExecutionProfile.defaults(), ExecutionProfile.defaults(), tools(),
                path -> new CodexModelCatalog(CodexModelCatalog.Status.AVAILABLE,
                        List.of(new CodexModelCatalog.Model("model-a", "A", false)))).orElseThrow();
        assertNull(result.profile().model());
    }

    private ProfileCli cli(ExecutionProfiles profiles, Supplier<String> input) {
        return new ProfileCli(profiles, directory, out, new RuntimePicker(input, out), this::tools,
                path -> new CodexModelCatalog(CodexModelCatalog.Status.AVAILABLE,
                        List.of(new CodexModelCatalog.Model("model-a", "Model A", true))));
    }
    private List<RuntimeDiscovery.Tool> tools() {
        return List.of(new RuntimeDiscovery.Tool("codex", "Codex", "codex", false, RuntimeDiscovery.State.FOUND, directory.resolve("codex")),
                new RuntimeDiscovery.Tool("claude", "Claude Code", "claude", false, RuntimeDiscovery.State.FOUND, directory.resolve("claude")),
                new RuntimeDiscovery.Tool("omx", "OMX", "codex", true, RuntimeDiscovery.State.MISSING, null));
    }
    private Supplier<String> inputs(String... values) { var queue = new ArrayDeque<>(List.of(values)); return queue::poll; }
}
