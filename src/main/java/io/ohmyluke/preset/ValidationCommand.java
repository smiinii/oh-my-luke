package io.ohmyluke.preset;

import io.ohmyluke.policy.ToolCapability;
import io.ohmyluke.tool.ProcessToolRequest;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/** Local, offline, sandboxed validation only; no shell string or caller environment. */
public record ValidationCommand(String executable, List<String> arguments, int expectedExitCode,
                                long timeoutMillis) {
    public ValidationCommand {
        executable = TaskSpec.text(executable, 2_048, "executable");
        arguments = List.copyOf(arguments);
        if (arguments.size() > 64 || expectedExitCode < 0 || expectedExitCode > 255
                || timeoutMillis < 1 || timeoutMillis > 300_000) {
            throw new IllegalArgumentException("invalid validation command bounds");
        }
        arguments.forEach(value -> {
            if (value.length() > 4_096) { throw new IllegalArgumentException("argument too long"); }
            TaskSpec.rejectSecrets(value);
        });
        request("validate-contract", executable, arguments, timeoutMillis);
    }

    public ProcessToolRequest request(String operationId) {
        return request(operationId, executable, arguments, timeoutMillis);
    }

    private static ProcessToolRequest request(String id, String executable, List<String> arguments, long timeout) {
        return new ProcessToolRequest(id, Path.of(executable), arguments, Path.of("."), Map.of(),
                Duration.ofMillis(timeout), 8_192, ToolCapability.LOCAL_PROCESS, "preset-validation");
    }
}
