package io.ohmyluke.tool;

import java.util.Objects;

/** Fail-closed adapter used when the platform sandbox dependency is unavailable. */
public final class UnavailableProcessSandbox implements ProcessSandbox {
    private final String reason;

    public UnavailableProcessSandbox(String reason) {
        this.reason = Objects.requireNonNull(reason, "reason");
        if (reason.isBlank()) {
            throw new IllegalArgumentException("reason must not be blank");
        }
    }

    @Override
    public boolean available() {
        return false;
    }

    @Override
    public String unavailableReason() {
        return reason;
    }

    @Override
    public SandboxLaunch prepare(ProcessSandboxSpec specification) {
        throw new IllegalStateException("sandbox is unavailable: " + reason);
    }
}
