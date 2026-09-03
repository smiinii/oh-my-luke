# 자동·수동 선택으로 작업 시작하기

`omluke start <job.json>`은 작업표를 읽고 **자동 또는 수동 선택이 끝난 뒤** 기존 Direct·Loop·정적 Workflow를 실행하는 공통 진입점이다. 선택에는 AI를 호출하지 않는다. 코드 없이 읽는 설명은 [마일스톤 10](how-it-works/10-auto-and-manual-start.md)에 있다.

## 자동과 수동은 무엇이 다른가

대화형 터미널에서 `--mode` 없이 시작하면 `1 자동 / 2 수동 / 0 취소`를 묻는다. 자동은 아래 규칙으로 실행 모드를 고르고, 수동은 허용 가능한 모드 중 사용자가 고른다. 선택이 완료되기 전에는 실행 상태를 만들거나 AI·파일 수정 작업을 시작하지 않는다.

스크립트·CI·입력 리다이렉션처럼 대화형 터미널이 아닌 환경에서는 `--mode auto|direct|loop|workflow`를 명시해야 한다. 값이 없으면 빠르게 오류로 끝나며, 기본 Auto로 실행하거나 파이프 입력을 메뉴 선택으로 소비하지 않는다. `--mode manual`은 없고, 실제 모드를 지정하면 수동 선택으로 기록한다.

Auto의 규칙 버전 1은 위에서부터 적용한다.

| 작업표의 명시 조건 | 선택 | 선택 이유 코드 |
| --- | --- | --- |
| `workflow` 선언이 있음 | Workflow | `auto-workflow-declared` |
| 단순 작업의 `approvalBeforeApply: true` | Workflow | `auto-approval-required` |
| 단순 작업의 `maxAttempts: 1` | Direct | `auto-single-attempt` |
| 단순 작업의 `maxAttempts`가 2~20 | Loop | `auto-bounded-retry` |

단순한 Workflow 선언도 축소하지 않고 그대로 보존한다. 파일 크기·목표 문장의 의미·프로젝트 스캔으로 난이도를 추측하거나, AI로 최적 모드를 찾는 기능은 아니다. **명시 조건을 실행 방식으로 옮기는 편의 기능**이며, 토큰 절감이나 최적 선택을 입증한 결과가 아니다.

수동 선택도 사용자의 기존 조건을 버리지 않는다.

- 승인이나 Workflow 선언이 없는 단순 작업: Direct·Loop·Workflow를 고를 수 있다. Direct는 `maxAttempts`를 1로 낮추고, Loop는 지정한 한도를 유지한다. Workflow는 단일 EDIT 단계의 고정 그래프를 구성한다.
- `approvalBeforeApply: true` 또는 이미 선언한 Workflow: Workflow만 허용한다. Direct/Loop를 명시하면 오류이며, 승인이나 선언 경로를 제거하지 않는다.
- 어떤 선택도 사용량·시간·시도 한도를 늘리거나 모델·도구 권한을 임의로 바꾸지 않는다. Workflow 진행 승인은 도구 권한 부여와 다르다.

## 새 작업표 형식

`StartSpec`은 `schemaVersion: 1`과 `task` 또는 `workflow` 중 **정확히 하나**를 가진다. 아래는 [기본 예제](../examples/start/task.json)와 같은 형식이다. `task`에는 `mode`가 없다.

```json
{
  "schemaVersion": 1,
  "task": {
    "goal": "Replace hello.txt with one line: OML_READY",
    "file": "hello.txt",
    "maxAttempts": 3,
    "maxUsage": 0,
    "maxElapsedMillis": 180000,
    "maxRepeatedFailures": 2,
    "validation": {
      "requiredText": ["OML_READY"],
      "forbiddenText": ["old"],
      "command": null
    },
    "model": null,
    "reasoning": null,
    "approvalBeforeApply": false
  },
  "workflow": null
}
```

기존 Workflow를 공통 진입점으로 실행하려면 `task: null`로 두고 `workflow`에 기존 `WorkflowSpec` 전체를 넣는다. EDIT 내부의 기존 `TaskSpec.mode`는 유지한다. 두 필드가 모두 없거나 둘 다 있으면 거부한다. 검증 명령 형식·문자열 조건·경로 제한은 [Direct/Loop 사용법](preset-usage.md), Workflow 선언 계약은 [Workflow 사용법](workflow-usage.md)을 따른다.

`file`은 현재 프로젝트 기준의 기존 파일이다. 작업표 자체, `.oml`, `.git`, 비밀 파일, 상위 경로는 수정할 수 없다. 한 번의 수정은 UTF-8 파일 하나·64 KiB 이하이며, 자연어 목표만 입력해서 파일이나 작업 계획을 자동 생성하는 기능은 아니다.

## 개발 빌드에서 작은 예제 실행

JDK 21과 Codex CLI 설치·로그인이 필요하다. 아래 `start` 명령은 **실제 AI를 호출하고 사용자 계정 사용량을 소비할 수 있다**. 저장소 자체 대신 복사한 임시 프로젝트를 사용한다. 한도 값은 최적화된 추천값이 아닌 작은 예제 값이다.

저장소 루트에서 실행한다. `OML_BIN`을 절대 경로로 저장하므로 임시 폴더로 옮긴 뒤에도 같은 개발 빌드를 실행한다.

```bash
./gradlew installDist
OML_REPO_DIR="$PWD"
OML_BIN="$OML_REPO_DIR/build/install/omluke/bin/omluke"
OML_START_DEMO="$(mktemp -d)"
cp examples/start/task.json examples/start/hello.txt "$OML_START_DEMO/"
cd "$OML_START_DEMO"
codex login status
"$OML_BIN" start task.json --run-id start-demo
"$OML_BIN" inspect start-demo
```

메뉴에서 `1`을 고르면 예제의 시도 한도 3에 따라 Loop로 시작한다. `2`를 고르면 실제 모드를 다시 선택한다. 빈 입력은 기본 선택으로 처리하지 않는다. 메뉴 입력이 3회 잘못되거나 필수 옵션이 없으면 종료 코드 2다. `0` 또는 EOF는 실행 없이 130으로 끝난다. 선택 완료는 곧 실행 시작이므로 실제 실행을 원하지 않으면 취소한다.

질문 없이 실행할 때는 아래 중 의도한 명령 하나를 사용한다. 이미 사용한 실행 ID를 재사용하지 않는다.

```bash
"$OML_BIN" start task.json --mode auto --run-id auto-demo
"$OML_BIN" start task.json --mode direct --run-id direct-demo
"$OML_BIN" start task.json --mode loop --run-id loop-demo
"$OML_BIN" start task.json --mode workflow --run-id workflow-demo
```

모델과 추론 강도는 사용자가 명시할 수 있다. 우선순위는 CLI 옵션 → 작업표 → 사용자 Codex 설정이며, OML이 실패 시 다른 모델로 자동 변경하지 않는다.

```bash
"$OML_BIN" start task.json --mode auto --model YOUR_MODEL --reasoning low
```

## 적용 전 승인 예제

[승인 예제](../examples/start/approval-task.json)는 `approvalBeforeApply: true`다. 위에서 저장한 저장소·실행 파일 경로를 사용해 별도의 임시 프로젝트에 복사한다. Auto는 Workflow를 선택하고 AI 수정안을 저장한 뒤 파일 적용 전에 멈춘다.

```bash
OML_APPROVAL_DEMO="$(mktemp -d)"
cp "$OML_REPO_DIR/examples/start/approval-task.json" "$OML_APPROVAL_DEMO/"
cp "$OML_REPO_DIR/examples/start/hello.txt" "$OML_APPROVAL_DEMO/"
cd "$OML_APPROVAL_DEMO"
"$OML_BIN" start approval-task.json --mode auto --run-id approval-demo
"$OML_BIN" inspect approval-demo
```

`result=WAITING_APPROVAL`, 종료 코드 3은 성공이나 실패가 아니라 승인 대기다. [제안 비교 절차](workflow-usage.md#실제-ai-파일-수정-연결)로 저장된 원본·수정안을 확인한다. 출력된 `approvalRequestId`로 `REQUEST_ID`를 바꾼다.

```bash
"$OML_BIN" approve approval-demo REQUEST_ID
"$OML_BIN" resume approval-demo
```

`approve`는 결정만 저장하고 `resume`이 다음 단계를 실행한다. 거부는 `deny approval-demo REQUEST_ID` 후 `resume approval-demo`다. 승인 대기 시간도 전체 시간 한도에 포함되며, 재시도에서 새 제안이 생기면 다시 승인해야 한다.

## 선택 기록과 재개

시작·확인·재개 시 선택 방식, 모드, 고정 이유 코드와 한국어 이유를 표시한다. 기본 예제의 Auto 선택은 다음과 같다.

```text
selectionStrategy=AUTO
mode=LOOP
selectionReason=auto-bounded-retry
selectionRuleVersion=1
```

수동 이유 코드는 `manual-direct`, `manual-loop`, `manual-workflow`다. `RunSelection`의 `ruleVersion`, `strategy`, `mode`, `reasonCode`는 확정된 작업표와 같은 실행 상태의 `execution.selection`에 저장된다. `inspect`는 저장 결과를 보여 주고, `resume`은 저장 작업표와 선택을 사용한다. 둘 다 메뉴를 다시 띄우거나 Auto 규칙을 재평가하지 않는다.

원본 작업표를 나중에 고쳐도 기존 실행은 바뀌지 않는다. 모드·모델·목표·한도를 바꾸려면 새 실행을 시작한다. `result=SUCCEEDED`만 작업 성공이며, 실패 시 이미 적용한 파일의 자동 롤백은 없다. 미보고 토큰, 경계 시점의 한도 초과와 중단 직후 중복 호출 가능성은 기존 [실행·재개 제한](preset-usage.md#결과와-재개)을 유지한다.

## 기존 명령과 검증 범위

- `omluke run <task.json>`: 기존 `TaskSpec`의 `mode`를 사용하는 명시적 Direct/Loop 진입점이다. 새 메뉴가 생기지 않는다.
- `omluke workflow <workflow.json>`: 기존 `WorkflowSpec`을 사용하는 명시적 Workflow 진입점이다. 선언을 Auto로 축소하지 않는다.
- `resume`, `inspect`, `cancel`, `approve`, `deny`: 기존 실행을 다루는 방식은 유지한다. 이전 실행에 새 선택 기록을 임의로 만들어 넣지 않는다.

기본 테스트와 CI는 실제 AI를 호출하지 않는다. 선택 규칙·한도/승인 보존·저장과 재개·예제 작업표는 `Start*Test`, 메뉴·비대화형 오류·취소·CLI 출력은 `StartPromptTest`와 `StartCliTest`로 확인한다. `StartProcessTest`는 AI 없는 승인·검사 Workflow를 실제 CLI 진입점의 별도 JVM들에서 실행해 저장 선택과 재개를 확인한다.

```bash
./gradlew test
```

이는 제어 흐름의 검증이지 실제 Codex 버전별 연동·모델 품질·토큰 절감 실험이 아니다. 자동 컨텍스트, 자연어 계획 생성, 동적/병렬 그래프와 공개 배포는 이번 범위에 포함하지 않는다.
