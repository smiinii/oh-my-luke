package io.ohmyluke.cli;

import io.ohmyluke.profile.ExecutionProfile;
import io.ohmyluke.profile.ExecutionProfiles;
import java.io.PrintStream;
import java.nio.file.Path;

/** Phase-one explicit settings interface. Discovery and the interactive runtime picker follow separately. */
final class ProfileCli {
    private final ExecutionProfiles profiles;
    private final Path project;
    private final PrintStream out;

    ProfileCli(ExecutionProfiles profiles, Path project, PrintStream out) {
        this.profiles = profiles;
        this.project = project;
        this.out = out;
    }

    int execute(String[] args) {
        if (args.length == 1 && args[0].equals("setup")) {
            profiles.resolve();
            out.println(profiles.setup() ? "OML 기본 설정을 준비했습니다." : "기존 설정을 그대로 유지했습니다.");
            out.println("이번 단계는 Codex + OML 설정을 지원합니다. 다른 실행기의 설정은 변경하지 않습니다.");
            return status();
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

    private int status() {
        var selection = profiles.resolve();
        out.println("선택 환경  Codex · OML");
        out.println("모델       " + (selection.profile().model() == null ? "실행기 설정 그대로 사용"
                : selection.profile().model() + " (명시값 · 계정 가용성 미확인)"));
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
