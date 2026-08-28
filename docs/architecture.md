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
- `GraphRunner`만 상태 변경분을 적용한다.
- 모든 실행 노드는 각 `Outcome`에 대해 정확히 하나의 `Condition`이 일치해야 하며, 겹치거나 비어 있는 분기는 실행 전에 거부한다.
- 각 이동은 상태 변경분, 적용 후 상태, 다음 노드와 선택 이유를 `TransitionEvent`로 기록한다.
- 상태와 이벤트의 맵은 불변이며 입력 및 상태 변경분의 반복 순서를 보존한다.
- 터미널 ID에 도달하면 성공하고, 최대 단계에 도달하면 성공과 구분된 상태로 안전 중단한다.
- 순환이 있는 그래프는 양수인 최대 단계 제한이 없으면 실행 전에 거부한다.

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
2. 상태 저장과 재개
3. 정책과 종료 조건
4. 범용 도구 노드
5. 로컬 프로젝트 스캐너
6. 가짜 AI 실행기
7. Codex CLI 실행기
8. Direct와 Loop 프리셋
9. 정적 Workflow와 비교 실험
