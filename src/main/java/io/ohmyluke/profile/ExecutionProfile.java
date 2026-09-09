package io.ohmyluke.profile;

import io.ohmyluke.tool.SecretRedactor;

/** One supported runtime/harness pair. A null model delegates to the runtime's own settings. */
public record ExecutionProfile(String runtime, String harness, String model) {
    public ExecutionProfile {
        if (!"codex".equals(runtime) || !"oml".equals(harness)) {
            throw new IllegalArgumentException("현재 지원하는 실행 조합은 Codex + OML입니다.");
        }
        if (model != null && (!model.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}")
                || !new SecretRedactor().redact(model, false).equals(model))) {
            throw new IllegalArgumentException("모델 ID 형식이 올바르지 않습니다.");
        }
    }

    public static ExecutionProfile defaults() { return new ExecutionProfile("codex", "oml", null); }
}
