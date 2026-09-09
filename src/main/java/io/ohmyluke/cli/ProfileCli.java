package io.ohmyluke.cli;

import io.ohmyluke.profile.ExecutionProfile;
import io.ohmyluke.profile.ExecutionProfiles;
import java.io.PrintStream;
import java.nio.file.Path;

/** Settings commands plus an optional metadata-only runtime/model picker. */
final class ProfileCli {
    private final ExecutionProfiles profiles;
    private final Path project;
    private final PrintStream out;
    private final RuntimePicker picker;
    private final java.util.function.Supplier<java.util.List<io.ohmyluke.profile.RuntimeDiscovery.Tool>> discovery;
    private final java.util.function.Function<Path, io.ohmyluke.ai.codex.CodexModelCatalog> models;

    ProfileCli(ExecutionProfiles profiles, Path project, PrintStream out) {
        this(profiles, project, out, RuntimePicker.system(out),
                () -> new io.ohmyluke.profile.RuntimeDiscovery(System.getenv("PATH"), project).scan(),
                executable -> new io.ohmyluke.ai.codex.CodexCatalogClient().load(executable,
                        project == null ? Path.of("").toAbsolutePath() : project));
    }

    ProfileCli(ExecutionProfiles profiles, Path project, PrintStream out, RuntimePicker picker,
               java.util.function.Supplier<java.util.List<io.ohmyluke.profile.RuntimeDiscovery.Tool>> discovery,
               java.util.function.Function<Path, io.ohmyluke.ai.codex.CodexModelCatalog> models) {
        this.profiles = profiles;
        this.project = project;
        this.out = out;
        this.picker = picker;
        this.discovery = discovery;
        this.models = models;
    }

    int execute(String[] args) {
        if (args.length == 1 && args[0].equals("runtimes")) {
            out.println("알려진 실행기·하네스의 PATH 탐지 결과 (프로그램은 실행하지 않습니다)");
            for (var tool : discovery.get()) {
                out.println(tool.label() + " · " + RuntimePicker.discoveryLabel(tool)
                        + (tool.command().equals("codex") ? " · OML 연결 지원" : " · 연결 준비 중")
                        + (tool.executable() == null ? "" : " · " + printable(tool.executable().toString())));
            }
            out.println("플러그인으로만 설치된 하네스·다른 앱·셸 별칭까지 전부 탐지한 목록은 아닙니다.");
            return 0;
        }
        if (args.length == 1 && args[0].equals("models")) {
            var codex = discovery.get().stream().filter(tool -> tool.command().equals("codex")).findFirst().orElseThrow();
            if (codex.state() != io.ohmyluke.profile.RuntimeDiscovery.State.FOUND) {
                out.println("Codex 실행 파일을 안전하게 찾지 못했습니다. omluke runtimes로 확인하세요."); return 1;
            }
            var catalog = models.apply(codex.executable());
            out.println(RuntimePicker.catalogMessage(catalog.status()));
            for (var model : catalog.models()) { out.println(model.model() + " · " + model.label() + (model.recommended() ? " · CLI 권장" : "")); }
            return catalog.status() == io.ohmyluke.ai.codex.CodexModelCatalog.Status.AVAILABLE ? 0 : 1;
        }
        if ((args.length == 1 || (args.length == 2 && args[1].equals("--interactive"))) && args[0].equals("switch")) {
            return choose(false);
        }
        if (args.length == 2 && args[0].equals("setup") && args[1].equals("--interactive")) { return choose(true); }
        if (args.length == 2 && args[0].equals("setup") && args[1].equals("--defaults")) { return initialize(); }
        if (args.length == 1 && args[0].equals("setup")) {
            if (picker.interactive() && profiles.selection(false) == null) { return choose(true); }
            return initialize();
        }
        if (args.length == 1 && args[0].equals("status")) { return status(); }
        if (!args[0].equals("switch") || args.length < 4 || !args[1].equals("--scope")
                || (!args[2].equals("global") && !args[2].equals("project"))) { return usage(); }
        boolean projectScope = args[2].equals("project");
        if (args.length == 4 && args[3].equals("--inherit") && projectScope) {
            profiles.resolve();
            profiles.resetProject();
            out.println("프로젝트 고정을 해제했습니다. 기본 설정을 따릅니다. 실행 기록은 유지했습니다.");
        } else {
            String model;
            if (args.length == 4 && args[3].equals("--inherit-model")) { model = null; }
            else if (args.length == 5 && args[3].equals("--model")) { model = args[4]; }
            else { return usage(); }
            ExecutionProfile profile = new ExecutionProfile("codex", "oml", model);
            profiles.resolve();
            if (projectScope) { profiles.saveProject(profile); } else { profiles.saveGlobal(profile); }
            out.println("선택을 저장했습니다. 이미 저장된 실행의 모델 선택은 바꾸지 않습니다.");
        }
        return status();
    }

    private int initialize() {
        profiles.resolve();
        out.println(profiles.setup() ? "OML 기본 설정을 준비했습니다." : "기존 설정을 그대로 유지했습니다.");
        out.println("Codex + OML 설정을 지원합니다. 다른 실행기의 설정은 변경하지 않습니다.");
        return status();
    }

    private int choose(boolean setup) {
        if (!picker.interactive()) { out.println("선택 화면은 대화형 터미널에서 실행하세요. 스크립트는 --scope와 --model/--inherit-model을 지정하세요."); return 2; }
        var current = profiles.resolve();
        ExecutionProfile expectedGlobal = profiles.selection(false);
        ExecutionProfile expectedProject = project == null ? null : profiles.selection(true);
        var choice = picker.choose(setup, project, current.profile(), expectedGlobal == null ? ExecutionProfile.defaults() : expectedGlobal,
                discovery.get(), models);
        if (choice.isEmpty()) { return 130; }
        var chosen = choice.get();
        boolean local = chosen.scope().equals("project");
        profiles.changeIfUnchanged(local, local ? expectedProject : expectedGlobal, chosen.inheritProject() ? null : chosen.profile());
        out.println("저장했습니다. 다른 CLI 설정·로그인·이미 시작한 실행은 변경하지 않았습니다.");
        return status();
    }

    private int status() {
        var selection = profiles.resolve();
        out.println("선택 환경  Codex · OML");
        out.println("모델       " + (selection.profile().model() == null ? "실행기 설정 그대로 사용"
                : selection.profile().model() + " (명시값 · 실제 사용 권한은 실행 시 확인)"));
        out.println("설정 출처  " + switch (selection.source()) {
            case "project" -> "프로젝트 고정";
            case "global" -> "사용자 기본 설정";
            default -> "최초 기본값";
        });
        out.println("기록 폴더  " + (project == null ? "미선택 — 작업 폴더에서 실행하거나 --project로 지정하세요."
                : printable(project.resolve(".oml/runs").toString())));
        out.println("OML로 시작하는 실행에 적용합니다. 다른 터미널·앱의 실행 상태를 뜻하지 않습니다.");
        return 0;
    }

    private int usage() {
        out.println("사용법: omluke setup | status");
        out.println("        omluke setup --defaults | setup --interactive | switch");
        out.println("        omluke runtimes | models");
        out.println("        omluke switch --scope global|project --model <모델ID>");
        out.println("        omluke switch --scope global|project --inherit-model");
        out.println("        omluke switch --scope project --inherit");
        return 2;
    }

    static String printable(String value) {
        return value.codePoints().collect(StringBuilder::new,
                (text, ch) -> text.appendCodePoint(Character.isISOControl(ch) ? '?' : ch), StringBuilder::append).toString();
    }
}
