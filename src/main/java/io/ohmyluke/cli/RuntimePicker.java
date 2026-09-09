package io.ohmyluke.cli;

import io.ohmyluke.ai.codex.CodexModelCatalog;
import io.ohmyluke.profile.ExecutionProfile;
import io.ohmyluke.profile.RuntimeDiscovery;
import java.io.Console;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

/** A cancellable draft: this screen never writes settings or starts an AI turn. */
final class RuntimePicker {
    record Choice(String scope, ExecutionProfile profile, boolean inheritProject) {}
    private record Item(String key, String label) {}
    private final Supplier<String> input;
    private final PrintStream out;

    RuntimePicker(Supplier<String> input, PrintStream out) { this.input = input; this.out = Objects.requireNonNull(out); }
    static RuntimePicker system(PrintStream out) {
        Console console = System.console();
        return new RuntimePicker(console == null ? null : console::readLine, out);
    }
    boolean interactive() { return input != null; }

    Optional<Choice> choose(boolean setup, Path project, ExecutionProfile current, ExecutionProfile global,
                            List<RuntimeDiscovery.Tool> tools, Function<Path, CodexModelCatalog> catalogLoader) {
        if (!interactive()) { throw new IllegalArgumentException("선택 화면은 대화형 터미널에서 실행하세요. 스크립트는 --scope와 --model/--inherit-model을 지정하세요."); }
        var codex = tools.stream().filter(tool -> tool.command().equals("codex")).findFirst().orElseThrow();
        if (codex.state() != RuntimeDiscovery.State.FOUND) {
            out.println("Codex 실행 파일을 안전하게 찾지 못했습니다. omluke runtimes로 PATH를 확인하세요.");
            return Optional.empty();
        }
        int step = 0;
        String selectedModel = current.model();
        String scope = "global";
        boolean inherit = false;
        CodexModelCatalog catalog = null;
        while (true) {
            out.println();
            out.println("OH MY LUKE · 실행 환경 선택");
            out.println("────────────────────────────────────────");
            String selected;
            if (step == 0) {
                out.println("1/4  실행기");
                for (var tool : tools) {
                    if (!tool.harness() && !tool.command().equals("codex")) {
                        out.println("  · " + tool.label() + " — " + discoveryLabel(tool) + " · 연결 준비 중");
                    }
                }
                selected = menu(List.of(new Item("codex", "Codex · 현재 지원 실행기")), false);
            } else if (step == 1) {
                out.println("2/4  하네스 · Codex");
                for (var tool : tools) {
                    if (tool.harness() && tool.runtime().equals("codex")) {
                        out.println("  · " + tool.label() + " — " + discoveryLabel(tool) + " · 연결 준비 중");
                    }
                }
                out.println("선택하지 않은 하네스의 외부 설정을 삭제하거나 수정하지 않습니다.");
                selected = menu(List.of(new Item("oml", "OML · 검증·재시도·재개 관리")), true);
            } else if (step == 2) {
                out.println("3/4  모델 · Codex / OML");
                if (catalog == null) {
                    out.println("Codex의 모델 목록을 확인합니다. AI 답변은 생성하지 않습니다.");
                    catalog = catalogLoader.apply(codex.executable());
                }
                out.println(catalogMessage(catalog.status()));
                if (catalog.status() == CodexModelCatalog.Status.AVAILABLE && catalog.models().isEmpty()) {
                    out.println("Codex가 표시 가능한 모델을 반환하지 않았습니다.");
                }
                var items = new ArrayList<Item>();
                items.add(new Item("inherit", "실행기 설정 그대로 사용" + (selectedModel == null ? " · 선택됨" : "")));
                for (var model : catalog.models()) {
                    items.add(new Item("model:" + model.model(), model.label() + " (" + model.model() + ")"
                            + (model.model().equals(selectedModel) ? " · 선택됨" : "")
                            + (model.recommended() ? " · CLI 권장" : "")));
                }
                if (selectedModel != null && catalog.models().stream().noneMatch(model -> model.model().equals(current.model()))
                        && Objects.equals(selectedModel, current.model())) {
                    items.add(new Item("model:" + selectedModel, "기존 선택 유지: " + selectedModel + " · 목록 확인 안 됨"));
                }
                selected = menu(items, true);
                if (selected != null && !selected.equals("back")) { selectedModel = selected.equals("inherit") ? null : selected.substring("model:".length()); }
            } else if (step == 3) {
                out.println("4/4  적용 범위");
                if (setup || project == null) {
                    out.println("사용자 기본 설정에 적용합니다." + (setup ? " 첫 설정에는 프로젝트 범위를 묻지 않습니다." : " 작업 폴더가 선택되지 않았습니다."));
                    scope = "global"; inherit = false; step = 4; continue;
                }
                if (project != null) { out.println("작업 폴더: " + ProfileCli.printable(project.toString())); }
                var items = new ArrayList<Item>();
                items.add(new Item("global", "사용자 기본 설정"));
                if (!setup && project != null) {
                    items.add(new Item("project", "이 작업 폴더만 고정"));
                    items.add(new Item("inherit", "이 작업 폴더는 사용자 기본 설정 따르기"));
                }
                selected = menu(items, true);
                if (selected != null && !selected.equals("back")) {
                    inherit = selected.equals("inherit"); scope = selected.equals("global") ? "global" : "project";
                }
            } else {
                ExecutionProfile profile = inherit ? global : new ExecutionProfile("codex", "oml", selectedModel);
                out.println("저장 전 확인");
                out.println("  Codex  /  OML  /  " + (profile.model() == null ? "실행기 설정 그대로 사용" : profile.model()));
                out.println("  적용: " + (scope.equals("global") ? "사용자 기본 설정" : inherit ? "프로젝트 고정 해제" : "이 작업 폴더만"));
                out.println("  이미 시작한 작업은 바뀌지 않습니다. 설치·로그인·AI 실행은 하지 않습니다.");
                selected = menu(List.of(new Item("save", "이 선택 저장")), true);
                if ("save".equals(selected)) { return Optional.of(new Choice(scope, profile, inherit)); }
            }
            if (selected == null) { out.println("취소했습니다. 설정을 변경하지 않았습니다."); return Optional.empty(); }
            step = selected.equals("back") ? (step == 4 && (setup || project == null) ? 2 : step - 1) : step + 1;
        }
    }

    private String menu(List<Item> items, boolean back) {
        int page = 0;
        int invalid = 0;
        while (invalid < 3) {
            int start = page * 8;
            for (int i = start; i < Math.min(start + 8, items.size()); i++) { out.println("  " + (i + 1) + ". " + items.get(i).label()); }
            out.println("  0. 취소" + (back ? "   b. 이전" : "") + (items.size() > 8 ? "   n/p. 다음/이전 페이지" : ""));
            out.print("선택 › "); out.flush();
            String line = input.get(); out.println();
            if (line == null) { return null; }
            String answer = line.strip().toLowerCase(java.util.Locale.ROOT);
            if (List.of("0", "q", "취소").contains(answer)) { return null; }
            if (back && answer.equals("b")) { return "back"; }
            if (answer.equals("n") && start + 8 < items.size()) { page++; invalid = 0; continue; }
            if (answer.equals("p") && page > 0) { page--; invalid = 0; continue; }
            if (answer.matches("[0-9]{1,3}")) {
                int index = Integer.parseInt(answer) - 1;
                if (index >= start && index < Math.min(start + 8, items.size())) { return items.get(index).key(); }
            }
            invalid++;
            out.println("보이는 번호를 선택하세요. 빈 입력으로 저장하거나 선택하지 않습니다.");
        }
        throw new IllegalArgumentException("입력이 3회 올바르지 않아 설정을 변경하지 않았습니다.");
    }

    static String discoveryLabel(RuntimeDiscovery.Tool tool) {
        return switch (tool.state()) { case FOUND -> "실행 파일 감지"; case MISSING -> "명령 미감지"; case BLOCKED -> "경로 확인 필요 · 자동 실행 차단"; };
    }
    static String catalogMessage(CodexModelCatalog.Status status) {
        return switch (status) {
            case AVAILABLE -> "출처: Codex 모델 목록 · 요금제로 추측하지 않습니다. 최종 사용 권한은 실행기가 확인합니다.";
            case LOGIN_REQUIRED -> "Codex 로그인이 필요합니다. codex login으로 로그인하세요. 기존 선택/상속만 저장할 수 있습니다.";
            case UNSUPPORTED_AUTH -> "ChatGPT 로그인 기반 목록이 아닙니다. API 키 과금으로 자동 전환하지 않습니다.";
            case UNAVAILABLE -> "모델 목록 조회 불가 · 기존 선택/상속만 유지할 수 있습니다. 로그인·CLI 버전을 확인하세요.";
        };
    }
}
