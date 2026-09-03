package io.ohmyluke.cli;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** Launches the real application in fresh JVMs; observes exec launches without changing AI output. */
final class WorkflowCliProcess {
    final Path project;
    private final Path evidence;
    private final Path shimDirectory;
    private final Path launches;
    private final Path realCodex;

    WorkflowCliProcess(Path directory, boolean live) throws Exception {
        project = Files.createDirectory(directory.resolve("project"));
        evidence = Files.createDirectory(directory.resolve("evidence"));
        shimDirectory = Files.createDirectory(directory.resolve("bin"));
        launches = evidence.resolve("codex-exec-launches.txt");
        realCodex = live ? findCodex() : null;
        String delegate = live ? "exec " + quote(realCodex.toString()) + " \"$@\"\n" : "exit 97\n";
        Path shim = shimDirectory.resolve("codex");
        Files.writeString(shim, "#!/bin/sh\nif [ \"$1\" = exec ]; then\n  printf 'exec\\n' >> "
                + quote(launches.toString()) + " || exit 98\nfi\n" + delegate);
        Files.setPosixFilePermissions(shim, PosixFilePermissions.fromString("rwx------"));
    }

    String requireChatGptLoginAndVersion() throws Exception {
        Result login = run(List.of(realCodex.toString(), "login", "status"), false);
        assertEquals(0, login.exitCode(), "Run codex login before enabling the live test");
        assertTrue(login.output().contains("Logged in using ChatGPT"),
                "This opt-in test requires saved ChatGPT login, not API-key billing");
        Result version = run(List.of(realCodex.toString(), "--version"), false);
        assertEquals(0, version.exitCode(), "Codex version probe failed");
        return version.output().lines().filter(line -> line.matches("codex-cli [a-zA-Z0-9.+-]+"))
                .findFirst().orElseThrow(() -> new AssertionError("Unrecognized Codex version output"));
    }

    Result cli(String... args) throws Exception {
        String classpath = Arrays.stream(System.getProperty("java.class.path").split(Pattern.quote(File.pathSeparator)))
                .map(entry -> Path.of(entry).toAbsolutePath().toString()).collect(java.util.stream.Collectors.joining(File.pathSeparator));
        List<String> command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", classpath, OmlukeApplication.class.getName()));
        command.addAll(List.of(args));
        return run(command, true);
    }

    long execLaunches() throws Exception {
        if (Files.notExists(launches)) { return 0; }
        try (var lines = Files.lines(launches)) { return lines.count(); }
    }

    private Result run(List<String> command, boolean useShim) throws Exception {
        Path output = Files.createTempFile(evidence, "cli-", ".txt");
        ProcessBuilder builder = new ProcessBuilder(command).directory(project.toFile())
                .redirectErrorStream(true).redirectOutput(output.toFile());
        // Preflight must use the same saved-login mode as the adapter, never an API-key override.
        builder.environment().remove("CODEX_API_KEY");
        builder.environment().remove("OPENAI_API_KEY");
        if (useShim) {
            builder.environment().put("PATH", shimDirectory + File.pathSeparator + System.getenv().getOrDefault("PATH", ""));
        }
        Process process = builder.start();
        process.getOutputStream().close();
        try {
            assertTrue(process.waitFor(useShim ? 330 : 15, TimeUnit.SECONDS), "CLI process timed out");
            assertTrue(Files.size(output) <= 64 * 1024, "Unexpectedly large CLI diagnostic output");
            return new Result(process.exitValue(), Files.readString(output));
        } finally {
            if (process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
        }
    }

    private static Path findCodex() {
        return Arrays.stream(System.getenv().getOrDefault("PATH", "").split(Pattern.quote(File.pathSeparator)))
                .filter(entry -> !entry.isBlank()).map(entry -> Path.of(entry).resolve("codex").toAbsolutePath())
                .filter(path -> Files.isRegularFile(path) && Files.isExecutable(path)).findFirst()
                .orElseThrow(() -> new AssertionError("Install the official Codex CLI before enabling the live test"));
    }

    private static String quote(String value) { return "'" + value.replace("'", "'\"'\"'") + "'"; }

    record Result(int exitCode, String output) {
        void expect(int code, String... fields) {
            assertEquals(code, exitCode, output);
            for (String field : fields) { assertTrue(output.lines().anyMatch(field::equals), output); }
        }
    }
}
