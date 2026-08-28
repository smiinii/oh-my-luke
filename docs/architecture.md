# Architecture

## 핵심 구조

```text
CLI
 ↓
Project & Context
 ↓
Preset Selection
 ↓
Graph Kernel
 ├── Runtime Node
 ├── Tool Node
 └── Human Node
 ↓
Policy & Limits
 ↓
State / Events / Artifacts
```

하네스, 루프, 그래프를 별도 엔진으로 구현하지 않는다.

```text
Graph Kernel
├── Direct Preset
├── Loop Preset
└── Workflow Preset
```

## 첫 번째 커널 슬라이스

현재 커널은 AI, 파일, 네트워크 없이 메모리에서 다음 규칙으로 동작한다.

- `Node`는 읽기 전용 `NodeContext`를 받고 `Outcome`과 `StatePatch`를 반환한다.
- `Node.fingerprint()`는 노드 종류, 동작 버전, 결과에 영향을 주는 설정을 안정적인 문자열로 제공한다.
- `GraphRunner`만 상태 변경분을 적용한다.
- 모든 실행 노드는 각 `Outcome`에 대해 정확히 하나의 `Condition`이 일치해야 하며, 겹치거나 비어 있는 분기는 실행 전에 거부한다.
- 각 이동은 상태 변경분, 적용 후 상태, 다음 노드와 선택 이유를 `TransitionEvent`로 기록한다.
- 상태와 이벤트의 맵은 불변이며 입력 및 상태 변경분의 반복 순서를 보존한다.
- 터미널 ID에 도달하면 성공하고, 최대 단계에 도달하면 성공과 구분된 상태로 안전 중단한다.
- 순환이 있는 그래프는 양수인 최대 단계 제한이 없으면 실행 전에 거부한다.

## 상태 저장과 재개 슬라이스

`ManagedRunService`가 순수한 `GraphRunner` 바깥에서 파일 저장과 실행 수명주기를 조정한다. 따라서 그래프 코어에는 파일 시스템이나 CLI 의존성이 들어가지 않는다.

```text
ManagedRunService
├── GraphRunner       한 단계 실행
├── CheckpointStore   state.json 원자적 교체와 백업 복구
├── EventLogStore     events.jsonl 추가 기록
└── HandoffStore      handoff.md 원자적 교체
```

한 노드를 실행하는 순서는 다음과 같다.

```text
READY 체크포인트
  → NODE_STARTED 체크포인트 저장
  → NODE_STARTED 이벤트 기록
  → 노드 실행
  → 새 READY 체크포인트 저장
  → NODE_COMPLETED 이벤트 기록
```

- `state.json`은 스키마 버전과 그래프 구조·노드 동작 지문의 SHA-256 서명을 가진다.
- 지원하지 않는 스키마는 백업으로 숨기지 않고 명시적으로 거부한다.
- 저장할 때도 현재 구현이 읽을 수 있는 스키마 버전만 허용한다.
- 파일 내부 `runId`와 실행 디렉터리의 ID가 다르면 상태 혼합으로 보고 거부한다.
- 손상된 기본 체크포인트는 마지막 `READY` 백업이 있을 때만 복구한다.
- `NODE_STARTED` 상태는 안전 백업을 덮어쓰지 않는다.
- 이벤트 로그는 UTF-8 문자의 중간을 포함해 완전히 기록되지 않은 마지막 JSONL 줄만 제거하고 복구한다. 중간 손상은 오류다.
- 재개할 그래프의 구조 서명이 다르면 노드를 실행하지 않는다.
- 취소 이벤트를 체크포인트보다 먼저 기록하므로 취소 상태 파일이 손상되어도 노드를 다시 실행하지 않는다.
- 체크포인트 저장 뒤 완료 이벤트 기록 전에 종료되면, 체크포인트의 전이 이력으로 누락된 완료 이벤트를 재구성한다.
- 실행 ID별 운영체제 파일 잠금으로 두 프로세스가 같은 노드를 동시에 실행하지 못하게 한다.
- 프로젝트 실제 경로를 기준으로 심볼릭 링크 탈출을 검사하고, 임시 파일은 충돌 없는 새 파일로 생성한다.
- 노드 자체의 외부 부작용은 정확히 한 번을 보장할 수 없다. 중단된 노드는 다시 실행될 수 있으므로 이후 도구 노드는 멱등성 키나 사전 조건을 제공해야 한다.

현재 독립 실행형 CLI는 저장된 Java 노드 구현을 파일만으로 복원할 수 없다. `inspect`와 `cancel`은 즉시 사용할 수 있고, `resume`은 `GraphResolver`로 실행 가능한 그래프가 등록된 환경에서 사용한다. 프리셋 단계에서 파일 기반 그래프 로딩을 연결한다.

## 초기 패키지 방향

```text
io.ohmyluke
├── cli
├── graph
├── state
├── policy
├── budget
├── project
├── context
├── tool
├── validator
├── runtime
├── node
└── preset
```

빈 패키지를 미리 만들지 않는다. 각 단계에서 실제 책임이 생길 때 추가한다.

## 구현 순서

1. 그래프 커널
2. 상태 저장과 재개 (완료)
3. 정책과 종료 조건
4. 범용 도구 노드
5. 로컬 프로젝트 스캐너
6. 가짜 AI 실행기
7. Codex CLI 실행기
8. Direct와 Loop 프리셋
9. 정적 Workflow와 비교 실험
