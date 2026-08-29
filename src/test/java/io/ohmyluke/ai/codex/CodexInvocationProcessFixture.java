package io.ohmyluke.ai.codex;

import io.ohmyluke.ai.AiRequest;
import io.ohmyluke.ai.AiRuntimeStatus;
import java.nio.file.Path;
import java.util.Map;

public final class CodexInvocationProcessFixture {
    private CodexInvocationProcessFixture() {}

    public static void main(String[] arguments) {
        if (arguments.length != 3) {
            throw new IllegalArgumentException("expected project, executable and invocation ID");
        }
        CodexCliRuntime runtime = new CodexCliRuntime(CodexCliConfiguration.forExecutable(
                Path.of(arguments[0]),
                Path.of(arguments[1])));
        var result = runtime.invoke(new AiRequest(arguments[2], "same", Map.of("ticket", "42")));
        if (result.status() != AiRuntimeStatus.SUCCESS || !"CROSS_JVM".equals(result.output())) {
            throw new IllegalStateException("Codex invocation did not complete successfully");
        }
    }
}
