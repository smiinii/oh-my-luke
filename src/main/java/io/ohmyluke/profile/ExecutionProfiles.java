package io.ohmyluke.profile;

import java.nio.file.Path;

/** Project override is a whole selection, not a merge of nullable fields. */
public final class ExecutionProfiles {
    private final ProfileStore global;
    private final ProfileStore project;

    public ExecutionProfiles(Path home, Path projectRoot) {
        global = new ProfileStore(home, false);
        project = projectRoot == null ? null : new ProfileStore(projectRoot, true);
    }

    public record Resolved(ExecutionProfile profile, String source) {}

    public Resolved resolve() {
        // Validate global settings as well: corruption must not hide behind an override.
        var user = global.load();
        var local = project == null ? java.util.Optional.<ExecutionProfile>empty() : project.load();
        if (local.isPresent()) { return new Resolved(local.get(), "project"); }
        return user.map(profile -> new Resolved(profile, "global"))
                .orElseGet(() -> new Resolved(ExecutionProfile.defaults(), "builtin"));
    }

    public boolean setup() { return global.initialize(); }
    public ExecutionProfile selection(boolean projectScope) { return (projectScope ? requireProject() : global).load().orElse(null); }
    public void changeIfUnchanged(boolean projectScope, ExecutionProfile expected, ExecutionProfile replacement) {
        if (!projectScope && replacement == null) { throw new IllegalArgumentException("사용자 기본 설정은 삭제하지 않습니다."); }
        (projectScope ? requireProject() : global).changeIfUnchanged(expected, replacement);
    }
    public void saveGlobal(ExecutionProfile profile) { global.save(profile); }
    public void saveProject(ExecutionProfile profile) { requireProject().save(profile); }
    public boolean resetProject() { return requireProject().reset(); }

    private ProfileStore requireProject() {
        if (project == null) { throw new IllegalStateException("프로젝트 작업 폴더를 먼저 지정해 주세요."); }
        return project;
    }
}
