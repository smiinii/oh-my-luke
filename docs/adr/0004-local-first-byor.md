# ADR 0004: Local-first BYOR

- Status: Accepted
- Date: 2026-08-25

## Context

사용자가 이미 구독하고 로그인한 공식 AI CLI를 활용하면서 중앙 OML 계정과 모델 비용을 만들지 않으려 한다.

## Decision

OML은 로컬 우선으로 실행하며 AI 연결은 `AiRuntime` 어댑터로 분리한다. 첫 실제 어댑터는 Codex CLI다.

## Consequences

- 사용자가 자신의 실행기 설치와 접근 권한을 준비한다.
- OML은 인증 정보를 저장하거나 공유하지 않는다.
- 실행기별 설치, 인증, 로그 형식 차이를 어댑터에서 처리한다.
