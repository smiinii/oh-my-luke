package io.ohmyluke.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@EnabledOnOs({OS.MAC, OS.LINUX})
class WorkflowCliProcessTest {
    @TempDir Path directory;

    @Test void guardForwardsOnlyOneExecButAllowsNonExecProbes() throws Exception {
        Path standIn = directory.resolve("stand-in");
        Files.writeString(standIn, "#!/bin/sh\nprintf 'delegated:%s\\n' \"$1\"\n");
        Files.setPosixFilePermissions(standIn, PosixFilePermissions.fromString("rwx------"));
        var fixture = new WorkflowCliProcess(directory, standIn);
        String guard = directory.resolve("bin/codex").toString();
        fixture.run(List.of(guard, "--version"), false).expect(0, "delegated:--version");
        fixture.run(List.of(guard, "exec"), false).expect(0, "delegated:exec");
        var blocked = fixture.run(List.of(guard, "exec"), false);
        blocked.expect(96);
        assertEquals("", blocked.output(), "the second exec must not reach the stand-in");
        fixture.run(List.of(guard, "login"), false).expect(0, "delegated:login");
        assertEquals(2, fixture.execLaunches(), "count attempts, including a blocked second launch");
    }
}
