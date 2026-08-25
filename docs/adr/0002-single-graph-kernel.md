# ADR 0002: Single Graph Kernel

- Status: Accepted
- Date: 2026-08-25

## Context

하네스, 루프, 그래프를 각각 구현하면 실행과 상태 관리가 중복된다.

## Decision

`GraphRunner` 하나를 실행 엔진으로 만들고 Direct와 Loop를 그래프 프리셋으로 제공한다.

## Consequences

- 하나의 상태와 이벤트 모델을 사용한다.
- 순환 경로에는 반드시 제한 조건이 필요하다.
- 그래프 코어는 AI 없이 단위 테스트할 수 있어야 한다.
