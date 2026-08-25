# ADR 0003: File Checkpoint

- Status: Accepted
- Date: 2026-08-25

## Context

MVP는 중앙 서버와 데이터베이스 없이 중단과 재개를 지원해야 한다.

## Decision

현재 상태는 JSON, 이벤트는 JSONL, 사람용 인수인계는 Markdown 파일에 저장한다.

## Consequences

- 로컬에서 실행 이력을 직접 확인할 수 있다.
- 저장은 임시 파일 작성 후 원자적으로 교체해야 한다.
- 스키마 버전과 손상 처리 전략이 필요하다.
