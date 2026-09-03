package io.ohmyluke.cli;

import io.ohmyluke.preset.RunSelection;
import io.ohmyluke.preset.StartChoice;
import io.ohmyluke.preset.StartSpec;
import java.io.Console;
import java.io.PrintStream;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/** An explicit, bounded pre-run choice. No console means no stdin reads or implicit consent. */
public final class StartPrompt {
    private final Supplier<String> input;

    private StartPrompt(Supplier<String> input) { this.input = input; }

    public static StartPrompt unavailable() { return new StartPrompt(null); }
    public static StartPrompt interactive(Supplier<String> input) { return new StartPrompt(Objects.requireNonNull(input)); }
    public static StartPrompt system() {
        Console console = System.console();
        return console == null ? unavailable() : interactive(console::readLine);
    }

    public boolean isInteractive() { return input != null; }

    Optional<StartChoice> choose(StartSpec spec, PrintStream out) {
        if (!isInteractive()) { throw new IllegalStateException("비대화형 실행에서는 --mode를 지정하세요."); }
        out.println("작업을 시작하기 전에 실행 방식을 선택하세요.");
        out.println("1. 자동 — 작업표의 조건으로 OML이 선택합니다.");
        out.println("2. 수동 — 실행 방식을 직접 선택합니다.");
        out.println("0. 취소");
        String strategy = answer(out, Set.of("1", "2", "auto", "manual", "자동", "수동"));
        if (strategy == null) { return Optional.empty(); }
        if (Set.of("1", "auto", "자동").contains(strategy)) { return Optional.of(StartChoice.AUTO); }

        Map<String, StartChoice> choices = new LinkedHashMap<>();
        if (spec.manualModes().size() == 1) {
            out.println("승인 또는 선언된 단계·경로를 유지하기 위해 WORKFLOW만 선택할 수 있습니다.");
        }
        int index = 1;
        for (RunSelection.Mode mode : spec.manualModes()) {
            StartChoice choice = StartChoice.valueOf(mode.name());
            choices.put(Integer.toString(index), choice);
            choices.put(mode.name().toLowerCase(Locale.ROOT), choice);
            String description = switch (mode) {
                case DIRECT -> "AI 한 번 실행 후 검증 (시도 한도 1회)";
                case LOOP -> "작업표의 한도 안에서 검증 실패 시 재시도";
                case WORKFLOW -> "정해진 단계·경로와 승인 조건 유지";
            };
            out.println(index++ + ". " + mode + " — " + description);
        }
        out.println("0. 취소");
        String answer = answer(out, choices.keySet());
        return answer == null ? Optional.empty() : Optional.of(choices.get(answer));
    }

    private String answer(PrintStream out, Set<String> choices) {
        for (int attempt = 0; attempt < 3; attempt++) {
            out.print("선택: ");
            out.flush();
            String line = input.get();
            // Keep subsequent machine-readable fields at the beginning of their own lines.
            out.println();
            if (line == null) { return null; }
            String value = line.strip().toLowerCase(Locale.ROOT);
            if (Set.of("0", "q", "cancel", "취소").contains(value)) { return null; }
            if (choices.contains(value)) { return value; }
            out.println("안내된 번호나 이름을 입력하세요. 빈 입력은 자동 선택으로 처리하지 않습니다.");
        }
        throw new IllegalArgumentException("유효하지 않은 선택이 3회 입력되어 새 실행을 시작하지 않았습니다.");
    }
}
