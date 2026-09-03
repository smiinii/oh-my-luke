# 정적 Workflow: 설계 이유와 개발용 실행

여러 단계의 조건 분기·순차 합류·사람 승인을 기존 그래프 커널에 연결한다. 실행 전에 경로를 고정하며 AI가 실행 중 노드나 검증 기준을 바꾸지는 않는다. 코드 없이 읽는 설명은 [마일스톤 9](how-it-works/09-static-workflow.md)에 있다.

## 왜 정적으로 만드는가

미리 정하는 것은 AI의 해결 방법이 아니라 **검사·재시도·승인·종료 순서**다. AI는 지정한 파일의 수정안을 판단한다.

| 얻는 점 | 감수하는 점과 대응 |
| --- | --- |
| 검증·반복·종료 기준이 명확하고 경로를 추적하기 쉽다 | 예상 밖 단계는 선언을 수정하고 새 실행으로 시작해야 한다 |
| 선검사가 통과하면 AI를 건너뛰고, 실패한 수정 단계만 다시 시도할 수 있다 | 불필요한 검사·재시도를 넣으면 오히려 시간·토큰이 늘어난다. 간단한 작업은 Direct/Loop를 유지한다 |
| 승인 대기와 재개 지점을 저장하고 실행을 통제한다 | 상태·승인·충돌 처리 구현이 늘어난다. 대기는 작업 실패와 별도로 관리한다 |
| 가짜 AI로 흐름을 빠르고 반복 가능하게 검증한다 | 실제 모델의 응답 품질·지연·인증·환경 문제는 실제 연결 테스트로 별도 확인해야 한다 |

정적 그래프 자체는 토큰 절약 기술이 아니라 **절약 전략을 넣고 검증하기 위한 기반**이다. 이 마일스톤은 토큰 절감률·품질 향상을 입증하지 않는다. [비교 실험](experiment.md)이 별도로 필요하다.

## 이번에 지원하는 선언

- `CHECK`: 기존 UTF-8 파일을 Java 문자열 조건 또는 고정 명령으로 검사한다. 참은 `onSuccess`, 검사 실패는 `onFailure`다. 권한 거부·검증 실행 불가·충돌은 거짓으로 위장하지 않고 중단한다.
- `EDIT`: 기존 Direct/Loop 작업표를 `task`에 넣는다. 파일 준비→AI 제안→적용→검증을 같은 그래프 안에 펼친다. 별도 실행 ID나 중첩 엔진을 만들지 않는다.
- `APPROVAL`: 지정한 지점에서 사람이 진행 여부를 정한다. 승인하면 `onSuccess`, 거부하면 `stopped`로 이동한다.
- `EDIT.approvalBeforeApply: true`: AI 제안을 저장한 뒤 파일 적용 전에 승인 대기한다. 재시도에서 새 제안이 나오면 새로운 승인이 필요하다.
- `succeeded`와 `stopped`는 예약 종료 이름이다. `succeeded`에는 CHECK/EDIT의 성공 경로만 연결할 수 있다. 승인이나 실패만으로 성공할 수 없다.

선택한 경로가 같은 후속 단계에 도착하는 **순차 합류**다. 두 경로를 동시에 실행하고 결과를 모으는 병렬 합류는 아니다. 외곽 선언은 순환을 거부하며, EDIT 내부만 기존 제한 재시도를 허용한다. 선행 성공 단계를 통째로 다시 실행하지 않는다.

## 한도와 계약

- 스키마 1, 최대 32개 선언 단계, JSON 512 KiB, `maxSteps` 1~4,096이다. 값은 최적화 결과가 아니라 현재 구현의 안전 상한이다.
- 전체 실행은 하나의 `maxUsage`와 `maxElapsedMillis`를 쓴다. EDIT 작업표의 두 값도 전체 값과 같아야 한다. 별도의 단계별 사용량·시간 예산으로 해석하지 않는다.
- 경과 시간은 **사람의 승인 대기 시간도 포함**하며 최대 1시간이다. 만료 시 승인해도 재개하지 않는다. 한도는 노드 경계에서 판정하므로 한 호출만큼 초과 가능하다.
- 시도·같은 실패 제한은 EDIT별로 유지한다. `maxUsage: 0`은 사용량 한도 비활성화이며, 켜져 있는데 토큰이 미보고되면 차단한다.
- 시작 시 선언을 상태에 저장한다. 원본 JSON을 바꿔도 진행 중 실행은 바뀌지 않는다. 목표·모델·경로·한도를 바꾸려면 새 실행을 만든다.
- 각 EDIT는 기존 파일 하나·UTF-8·64 KiB 제한을 유지한다. 여러 EDIT를 순차 연결할 수 있지만 트랜잭션·자동 롤백·전체 파일의 무변경 보장은 없다. 최종 CHECK를 실제 완료 기준으로 설계한다.

## AI 없이 승인·재개 확인

다음 예제는 Codex 설치·로그인·토큰 사용 없이 작동한다. 저장소 루트에서 빌드하고 임시 프로젝트에서 실행한다.

```bash
./gradlew installDist
OML_BIN="$PWD/build/install/omluke/bin/omluke"
OML_WORKFLOW_DEMO="$(mktemp -d)"
cp examples/workflows/check-and-approve.json "$OML_WORKFLOW_DEMO/workflow.json"
cp examples/workflows/ready.txt "$OML_WORKFLOW_DEMO/hello.txt"
cd "$OML_WORKFLOW_DEMO"
"$OML_BIN" workflow workflow.json --run-id demo
"$OML_BIN" inspect demo
```

처음 실행은 `result=WAITING_APPROVAL`, 종료 코드 **3**이다. 실패나 성공이 아니라 사용자 입력이 필요하다는 뜻이다. `approvalRequestId`를 확인하고 아래의 `REQUEST_ID`를 실제 값으로 교체한다.

```bash
"$OML_BIN" approve demo REQUEST_ID
"$OML_BIN" resume demo
```

`approve`는 결정만 저장한다. 별도 `resume`에서 검사를 실행해 `SUCCEEDED`가 된다. 거부하려면 `deny demo REQUEST_ID` 후 `resume demo`로 중단 경로를 마무리한다. 대기 중 `resume`을 반복해도 승인 없이 진행하거나 카운터를 소비하지 않는다.

## 실제 AI 파일 수정 연결

[수정 예제 JSON](../examples/workflows/edit-with-approval.json)은 선검사→필요한 경우 수정→적용 승인→최종 검사를 연결한다. 작은 별도 폴더로 예제 JSON과 [초기 파일](../examples/presets/hello.txt)을 복사하고 현재 폴더를 그곳으로 옮겨 실행한다. 초기 파일의 이름은 `hello.txt`다.

```bash
codex login status
omluke workflow edit-with-approval.json --run-id edit-demo
omluke inspect edit-demo
omluke approve edit-demo REQUEST_ID
omluke resume edit-demo
```

개발 빌드에서는 `omluke` 대신 앞에서 만든 `"$OML_BIN"`을 사용한다. 이 예제는 실제 사용자 계정 사용량이 발생할 수 있다. 모델은 `--model YOUR_MODEL --reasoning low`로 선택한다. 우선순위는 CLI 공통 재정의→각 EDIT 작업표→Codex 설정이며, 재개에서는 처음 선택을 유지한다.

현재 전용 diff 미리보기 명령은 없다. 적용 승인 전 `state.json`의 `state.values`에서 `workflow.step.edit.preset.currentHash`와 `proposalHash`를 확인하고, `.oml/runs/edit-demo/artifacts/preset-content/<해시>.txt` 두 파일을 에디터로 비교한다. 다른 단계 이름이라면 `edit`를 해당 ID로 바꾼다. 승인 요청 문구만 보고 변경 내용을 검토했다고 간주하지 않는다.

## 승인과 복구의 의미

- 요청 ID는 실행 ID·그래프 서명·노드 방문 횟수·입력 상태 해시에 결속된다. 오래된 요청이나 다른 실행의 요청, 중복/상충 결정은 거부한다.
- Workflow 승인은 **이 지점 이후 흐름을 진행해도 된다는 동의**다. 도구 권한의 `이번만/실행/프로젝트` 승인과 다르며, 불변 차단·샌드박스·권한 정책을 바꾸지 않는다. 범용 권한 선택 UI는 아직 없다.
- 적용 전 승인은 저장된 제안과 기준 파일에 결속된다. 대기 중 원본이 바뀌면 적용 시 충돌로 차단한다. 다른 모든 파일까지 동결하는 기능은 아니다.
- 결정 이벤트를 먼저 기록하여 체크포인트 저장 전 중단·백업 복구에서도 결정을 되살린다. 취소가 승인보다 우선한다. 저장 규칙은 [코어 실행과 복구](core-runtime.md)를 따른다.
- `inspect`/`cancel`은 그래프 재구성 없이 저장 상태를 사용하며, `approve`/`deny`/`resume`은 저장 선언으로 동일 그래프를 복원한다. 실행 잠금 충돌 시 작업을 빼앗거나 대기하지 않고 오류를 알린다.
- `workflow`/`resume`: 성공 0, 승인 대기 3, 나머지 비성공 1. `approve`/`deny`의 0은 결정 기록 성공이지 작업 완료가 아니다.

## 검증 근거와 남은 일

`WorkflowSpecTest`, `WorkflowRunServiceTest`, `WorkflowSafetyTest`, `WorkflowExamplesTest`, `WorkflowCliTest`가 선언·분기·선검사·재시도·권한·한도·명령·예제를 검증한다. `ManagedApprovalTest`는 강제 JVM 종료 후 새 JVM 재개, 오래된 승인, 상태 손상·이벤트 복구를 검증한다.

가짜 AI 테스트는 OML의 제어 흐름을 검증하며 실제 모델 품질 실험이 아니다. 실제 Codex 단일 파일 연결 근거는 [기존 마일스톤 8](how-it-works/08-direct-and-loop.md)을 유지한다. Workflow의 별도 JVM·실제 Codex 통합 검증은 [실연동 검증 기록](workflow-verification.md)에 재현 명령과 결과를 남긴다. 기본 테스트/CI에서는 실제 AI를 호출하지 않는다.

Auto 모드·자동 컨텍스트·동적/병렬 그래프·벤치마크·공개 배포는 남아 있다. 운영체제별 파일/프로세스·Codex 경계와 exactly-once 한계는 [보안](security.md)과 [Direct/Loop 사용법](preset-usage.md)의 제한을 그대로 따른다.
