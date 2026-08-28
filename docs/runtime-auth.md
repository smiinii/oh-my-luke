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
