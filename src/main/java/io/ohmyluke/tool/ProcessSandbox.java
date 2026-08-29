package io.ohmyluke.tool;

/** Platform boundary that must be verified before ProcessTool executes anything. */
public interface ProcessSandbox {
    boolean available();

    String unavailableReason();

    SandboxLaunch prepare(ProcessSandboxSpec specification);
}
