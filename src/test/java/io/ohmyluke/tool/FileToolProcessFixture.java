package io.ohmyluke.tool;

import io.ohmyluke.policy.PermissionGrantLedger;
import io.ohmyluke.policy.ToolPermissionPolicy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;

/** Separate-JVM fixture proving that the durable mutation lock coordinates independent OML processes. */
public final class FileToolProcessFixture {
    private FileToolProcessFixture() {}

    public static void main(String[] arguments) throws Exception {
        Path project = Path.of(arguments[0]);
        Path ready = Path.of(arguments[1]);
        Path start = Path.of(arguments[2]);
        Files.writeString(ready, "ready");
        while (Files.notExists(start)) {
            Thread.sleep(5);
        }
        ToolPermissionPolicy permissions = new ToolPermissionPolicy(
                new PermissionGrantLedger(List.of()), project, false, Clock.systemUTC());
        FileTool tool = new FileTool(project, "cross-process-run", permissions, Clock.systemUTC());
        FileToolResult result = tool.execute(FileToolRequest.createDirectory(
                "cross-process-create", project.resolve("created")));
        if (!result.executed()) {
            System.err.println(result.detail());
            System.exit(1);
        }
    }
}
