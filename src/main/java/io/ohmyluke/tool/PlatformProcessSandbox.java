package io.ohmyluke.tool;

import java.util.Locale;

/** Selects only sandbox implementations that can enforce the current operating-system boundary. */
public final class PlatformProcessSandbox {
    private PlatformProcessSandbox() {}

    public static ProcessSandbox detect() {
        String operatingSystem = System.getProperty("os.name", "unknown").toLowerCase(Locale.ROOT);
        if (operatingSystem.contains("mac") || operatingSystem.contains("darwin")) {
            return new MacOsSeatbeltSandbox();
        }
        if (operatingSystem.contains("linux")) {
            return new LinuxBubblewrapSandbox();
        }
        return new UnavailableProcessSandbox(
                "no verified OML process sandbox is available for " + System.getProperty("os.name", "unknown"));
    }
}
