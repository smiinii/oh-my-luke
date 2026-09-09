package io.ohmyluke.profile;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

/** Known-command discovery, not a whole-computer scan or an executable provenance check. Never spawns a process. */
public final class RuntimeDiscovery {
    public enum State { FOUND, MISSING, BLOCKED }
    public record Tool(String command, String label, String runtime, boolean harness, State state, Path executable) {}
    private final String searchPath;
    private final Path project;

    public RuntimeDiscovery(String searchPath, Path project) {
        this.searchPath = searchPath;
        this.project = project == null ? null : ProjectLocator.canonical(project);
    }

    public List<Tool> scan() {
        return List.of(find("codex", "Codex", "codex", false), find("claude", "Claude Code", "claude", false),
                find("opencode", "OpenCode", "opencode", false), find("omx", "OMX", "codex", true),
                find("omc", "OMC", "claude", true), find("omo", "OMO", "opencode", true));
    }

    private Tool find(String command, String label, String runtime, boolean harness) {
        if (searchPath == null) { return new Tool(command, label, runtime, harness, State.MISSING, null); }
        String[] entries = searchPath.split(Pattern.quote(File.pathSeparator), -1);
        if (searchPath.length() > 32_768 || entries.length > 256) { return blocked(command, label, runtime, harness); }
        for (String entry : entries) {
            try {
                Path directory = Path.of(entry);
                Path path = directory.resolve(command + (File.separatorChar == '\\' ? ".exe" : ""));
                if (!Files.isRegularFile(path) || !Files.isExecutable(path)) { continue; }
                Path real = path.toRealPath();
                if (!directory.isAbsolute() || (project != null && (path.toAbsolutePath().normalize().startsWith(project)
                        || real.startsWith(project)))) { return blocked(command, label, runtime, harness); }
                return new Tool(command, label, runtime, harness, State.FOUND, real);
            } catch (java.io.IOException | java.nio.file.InvalidPathException | SecurityException error) {
                return blocked(command, label, runtime, harness);
            }
        }
        return new Tool(command, label, runtime, harness, State.MISSING, null);
    }

    private Tool blocked(String command, String label, String runtime, boolean harness) {
        return new Tool(command, label, runtime, harness, State.BLOCKED, null);
    }
}
