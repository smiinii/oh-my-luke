package io.ohmyluke.state;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HandoffStoreTest {
    @TempDir
    Path projectRoot;

    @Test
    void writesHumanReadableHandoffAtomically() throws IOException {
        HandoffStore store = new HandoffStore(projectRoot);
        HandoffNote note = new HandoffNote(
                "인증 테스트 전체 통과",
                List.of("만료 토큰 갱신 경로에서 실패"),
                List.of("TokenService.java"),
                List.of("refreshesExpiredToken"),
                List.of("검증 없이 예외를 무시하지 않는다"),
                "실패 테스트를 재현하고 갱신 조건을 확인한다");

        store.save("run-001", note);

        String markdown = Files.readString(store.handoffPath("run-001"));
        assertTrue(markdown.startsWith("# Handoff\n"));
        assertTrue(markdown.contains("- 목표: 인증 테스트 전체 통과"));
        assertTrue(markdown.contains("- 지금까지 확인한 사실:\n  - 만료 토큰 갱신 경로에서 실패"));
        assertTrue(markdown.contains("- 변경한 파일:\n  - `TokenService.java`"));
        assertTrue(markdown.contains("- 남은 실패:\n  - `refreshesExpiredToken`"));
        assertTrue(markdown.contains("- 하지 말아야 할 시도:\n  - 검증 없이 예외를 무시하지 않는다"));
        assertTrue(markdown.contains("- 다음 행동: 실패 테스트를 재현하고 갱신 조건을 확인한다"));
        assertFalse(Files.exists(store.handoffPath("run-001").resolveSibling("handoff.md.tmp")));
    }
}
