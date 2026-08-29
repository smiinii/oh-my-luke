# Runtime and Authentication

## BYOR

OML은 사용자 소유 AI 실행기 방식(BYOR: Bring Your Own Runtime)을 사용한다.

```text
사용자 목표
→ OML
→ 사용자의 공식 AI CLI
→ 사용자의 계정과 구독 권한
```

OML은 사용자의 비밀번호나 인증 토큰을 소유하지 않는다. 설치와 로그인 상태는 가능한 경우 공식 CLI 명령으로 확인한다.

범용 프로세스 도구는 호스트의 로그인 환경과 인증 파일을 상속하지 않는다. 이후 `CodexCliRuntime`처럼 인증이 필요한 공식 실행기는 전용 어댑터가 사용자의 기존 로그인 세션을 중계하되 실제 토큰 값을 AI 문맥, 상태, 이벤트나 명령 인자에 넣지 않는다.

## 첫 실행기

첫 실제 어댑터는 `CodexCliRuntime`이다. 그래프 코어는 Codex에 의존하지 않으며 실제 연결 전에 `FakeAiRuntime`으로 검증한다.

```text
AiRuntime
├── FakeAiRuntime
└── CodexCliRuntime
```

향후 Claude CLI, Gemini CLI, Ollama 또는 선택적인 HTTP API 어댑터를 같은 경계 뒤에 추가할 수 있다.

현재 `AiRuntime` 계약과 `FakeAiRuntime`이 구현되어 있다. 가짜 실행기는 예상 요청과 결과 목록을 메모리에서 순서대로 비교하며 실제 AI, 네트워크, CLI 또는 인증정보에 접근하지 않는다.

- `AiNode`는 그래프 상태 전체가 아니라 설정에서 고른 키만 요청 문맥으로 전달한다.
- 예상 요청이 다르면 다음 결과를 소비하지 않고 구조화된 실패를 반환한다.
- 성공 출력은 지정된 상태 키로 전달하고 사용량은 정책 카운터에 반영한다.
- 가짜 실행기의 스크립트 위치는 메모리에만 있다. 새 인스턴스를 만들면 첫 응답부터 시작하므로 현재는 영속 재개용 실행기가 아니라 반복 가능한 테스트 실행기다.
