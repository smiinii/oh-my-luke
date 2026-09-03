# Runtime and Authentication

## BYOR

OML은 사용자 소유 AI 실행기 방식(BYOR: Bring Your Own Runtime)을 사용한다.

```text
사용자 목표
→ OML
→ 사용자의 공식 AI CLI
→ 사용자의 저장 로그인과 계정 권한
```

OML은 사용자의 비밀번호나 인증 토큰을 소유하지 않는다. 설치와 로그인 상태는 가능한 경우 공식 CLI 명령으로 확인한다.

범용 프로세스 도구는 호스트의 로그인 환경과 인증 파일을 상속하지 않는다. `CodexCliRuntime`처럼 인증이 필요한 공식 실행기만 전용 어댑터가 사용자의 기존 로그인 세션을 중계하되 실제 토큰 값을 AI 문맥, 상태, 이벤트나 명령 인자에 넣지 않는다.

## 첫 실행기

첫 실제 어댑터는 `CodexCliRuntime`이다. 그래프 코어는 Codex에 의존하지 않으며 실제 연결 전에 `FakeAiRuntime`으로 검증한다.

```text
AiRuntime
├── FakeAiRuntime
└── CodexCliRuntime
```

향후 Claude CLI, Gemini CLI, Ollama 또는 선택적인 HTTP API 어댑터를 같은 경계 뒤에 추가할 수 있다.

현재 `AiRuntime`, `FakeAiRuntime`, `CodexCliRuntime`이 구현되어 있다. 가짜 실행기는 실행 ID·노드·단계로 만든 논리 호출 ID에 예상 요청과 결과를 연결하며 실제 AI, 네트워크, CLI 또는 인증정보에 접근하지 않는다.

- `AiNode`는 그래프 상태 전체가 아니라 설정에서 고른 키만 요청 문맥으로 전달한다.
- 같은 논리 호출을 재시도하면 같은 결과를 돌려주며, 예상 요청의 내용이 다르면 구조화된 실패를 반환한다.
- 성공 출력은 지정된 상태 키로 전달하고 사용량은 정책 카운터에 반영한다.
- 실행별 호출 ID가 다르므로 하나의 가짜 실행기를 공유해도 실행끼리 결과를 빼앗지 않는다. 같은 스크립트로 새 인스턴스를 만들어도 중간 호출을 직접 재생할 수 있다.
- AI 노드는 명시적인 run ID가 있어야 실행기를 호출한다. 범위 없음은 문자열 예약어와 구분되며 관리 런타임은 저장된 실행 ID를 자동으로 전달한다.
- 실패 원문은 체크포인트에 전달하지 않는다. `AiFailureCode` 허용 목록의 코드와 미리 정한 안전 메시지만 `FailureInfo`로 변환한다.

### AI 노드의 기술 계약

- 요청은 run ID·노드 ID·실행 단계로 해시한 호출 ID, 지시문, 선택한 문맥으로 구성한다. 문맥 키는 정렬한 불변 값이며 `local`을 포함한 유효 실행 ID와 범위 없음 상태를 혼동하지 않는다.
- 가짜 스크립트는 논리 호출 ID로 결과를 찾는다. 요청 내용 불일치와 미등록 호출은 서로 다른 실패 코드다.
- AI 노드와 가짜 실행기의 지문은 길이 접두어 직렬화·SHA-256으로 설정, 논리 요청 집합, 결과와 사용량 차이를 구분한다.
- AI 출력 상태 반영과 `ExecutionMetrics` 보고 뒤의 누적·한도 판정은 [코어 실행과 복구](core-runtime.md)가 담당한다. 가짜 실행기의 메모리 재생과 실제 Codex 호출의 영속 결과 저장은 별개다.

## Codex CLI 연결

사용자는 공식 CLI에서 한 번 로그인한다.

```bash
codex login
codex login status
```

`CodexCliRuntime.probe()`는 `codex --version`과 `codex login status`의 성공 여부만 확인한다. 이 성공 여부만으로 ChatGPT 로그인과 CLI가 지원하는 다른 저장 인증 방식을 구분하지는 않는다. OML은 `~/.codex/auth.json`, 운영체제 자격 증명 저장소 또는 실제 토큰 값을 읽지 않는다.

실제 호출은 공식 비대화형 모드와 JSONL 출력을 사용한다.

```text
codex exec --json --ephemeral
  --sandbox read-only
  --skip-git-repo-check
  --cd <민감 파일을 제외한 임시 프로젝트 사본>
  -
```

프롬프트는 표준입력 JSON으로 전달하며 명령은 셸 문자열이 아닌 `ProcessBuilder` 인자 목록으로 구성한다. 자식 환경은 `HOME`, `CODEX_HOME`, `PATH`, 임시 경로, 언어, 운영체제 실행 경로, 인증서와 인증정보가 없는 프록시 변수의 허용 목록으로 다시 구성한다. 이름을 미리 알지 못한 서비스 비밀 변수도 기본 제거하며, 공식 CLI는 허용된 경로를 통해 기존 저장 로그인을 찾는다.

사본은 `.git`, `.oml`, 민감 파일과 빌드 산출물을 제외한다. `.git` 없는 사본에서 실행하므로 `--skip-git-repo-check`를 사용한다.

임시 사본은 기본 프로젝트 문맥을 줄이고 원본 프로젝트에 대한 Codex 셸 명령의 쓰기를 막기 위한 경계다. `--sandbox read-only`는 Codex가 상속한 설정, MCP 서버, 웹 기능 또는 같은 사용자 권한의 다른 읽기 통로까지 차단하는 기밀성 샌드박스가 아니다. 그런 읽기 격리가 필요한 비교 실험은 별도 HOME·설정과 운영체제 샌드박스로 구성해야 한다.

## 모델 선택

기본값은 `inherit`다.

```text
실행별 명시 모델/추론 강도
→ 없으면 사용자 Codex 설정
```

- 명시적 모델은 `--model <사용자 입력>`으로 전달한다.
- 명시적 추론 강도는 `--config model_reasoning_effort="..."`로 전달한다.
- OML은 빠르게 바뀌는 모델 목록을 자체 허용 목록으로 복제하지 않는다. 모델 이름의 문자열·제어문자 경계를 검사하고, 실제 사용 가능 여부는 사용자의 CLI와 계정이 판정한다.
- Java 런타임 설정과 `omluke run <task.json> --model <모델> --reasoning <강도>`에서 선택한다. CLI 선택 → 작업표 선택 → Codex 설정 순이며, 실행 시작 시 작업표에 저장한 선택으로 재개한다.

## 실행 결과와 사용량

JSONL에서 최종 에이전트 메시지, Codex 세션 ID와 `turn.completed.usage`를 읽는다. 캐시 입력과 추론 출력은 각각 입력·출력의 하위 항목으로 별도 기록하고, 정책용 전체 기록 토큰은 입력과 출력만 더한다. 사용량 필드가 일부 없거나 알 수 없는 스키마이면 응답 성공은 보존하고 사용량만 `unavailable`로 둔다.

완료 결과는 요청·OML 런타임 설정 지문에 결속해 OML 내부에 저장한다. 같은 논리 호출은 저장 결과를 재사용하고 다른 요청이나 OML의 명시적 모델 설정이 같은 ID를 사용하면 실행 전에 거부한다. `inherit`의 실제 모델·추론 값과 CLI 버전은 공식 CLI 내부 값이라 지문에 포함되지 않으므로, 사용자가 그 외부 값을 바꾼 뒤 새 응답을 원하면 새 논리 호출 ID를 사용해야 한다.

실제 AI 테스트는 경로별로 명시적으로 켠다. 기본 테스트와 CI에서는 모두 비활성화된다.

- 런타임·프리셋: `OML_CODEX_INTEGRATION=true`
- [Workflow 승인·재개](workflow-verification.md): `OML_WORKFLOW_CODEX_INTEGRATION=true`
- [Start 자동·수동 선택 연결](start-verification.md): `OML_START_CODEX_INTEGRATION=true`

하나를 확인할 때는 다른 플래그를 끄고 문서의 `--tests` 대상으로 범위를 좁힌다. 이 기록은 연결·집계 검증이며 Baseline과 비교한 절감 결과가 아니다.

저장 위치는 `.oml/runtime/codex/invocations/`다. 원자적 파일 교체와 JVM·운영체제 파일 잠금으로 같은 논리 호출의 동시 중복 실행을 막는다. 제공자 stderr·예외 원문은 저장하지 않고 안전한 실패 코드로만 분류한다.

## 개발용 연결 검증

저장소 루트에서 실행하는 런타임 단독 통합 테스트다. 저장된 로그인과 실제 AI 사용량을 사용하며 기본 테스트와 CI에서는 비활성화된다. 파일 적용·검증까지 확인하는 `PresetCodexIntegrationTest`는 [프리셋 사용법](preset-usage.md)을 따른다.

```bash
codex login status
OML_CODEX_INTEGRATION=true ./gradlew test \
  --tests io.ohmyluke.ai.codex.CodexCliRuntimeIntegrationTest
```

공식 근거:

- [Codex 인증](https://learn.chatgpt.com/docs/auth)
- [Codex 비대화형 실행](https://learn.chatgpt.com/docs/non-interactive-mode)
- [Codex CLI 명령과 옵션](https://learn.chatgpt.com/docs/developer-commands?surface=cli)
