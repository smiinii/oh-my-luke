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
