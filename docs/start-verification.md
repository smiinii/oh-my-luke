# Start 실제 Codex 연결 검증

새 `omluke start` 진입점이 선택한 실행 방식으로 실제 Codex를 호출하고, 파일 적용·Java 검증·승인·재개·사용량 기록을 연결하는지 확인한다. [이슈 #22](https://github.com/smiinii/oh-my-luke/issues/22)의 작업이며 [마일스톤 10](how-it-works/10-auto-and-manual-start.md)의 검증 보충이다. 새 배포 기능이나 Baseline 대비 절감 실험은 아니다.

## 무엇을 검사하나

[StartCodexIntegrationTest](../src/test/java/io/ohmyluke/cli/StartCodexIntegrationTest.java)는 작은 임시 프로젝트의 `hello.txt`를 `OML_START_OK`로 바꾸는 작업표를 사용한다. 실제 AI 시나리오는 모두 최대 시도 1회다.

| 시나리오 | 선택 결과 | 기대 흐름 | 실제 Codex 실행 상한 |
| --- | --- | --- | ---: |
| Auto·단일 시도 | `AUTO / DIRECT` | 제안→적용→문자열 검증 성공 | 1회 |
| 수동 Loop·단일 시도 | `MANUAL / LOOP` | 지정 모드 유지→제안→적용→검증 성공 | 1회 |
| Auto·적용 전 승인 | `AUTO / WORKFLOW` | 제안 저장→승인 대기→결정 저장→재개 후 적용·검증 | 1회 |
| 명시한 CHECK 전용 Workflow | `AUTO / WORKFLOW` | Java 문자열 검사만으로 성공 | 0회 |

마지막 행은 AI 없는 기본 테스트다. 일반 Direct/Loop에는 선검사가 없으므로, 파일이 이미 맞으면 언제나 AI를 생략한다는 뜻은 아니다.

`start`, `inspect`, `approve`, `resume`마다 **실제 OML 진입점을 새 JVM으로 실행**한다. 처음 실행한 뒤 원본 작업표를 잘못된 JSON으로 바꿔도 저장된 작업표·선택으로 재개하는지 확인한다. 승인 대기 중에는 원본 파일을 보존하며, `approve`는 결정만 저장하고 `resume`이 적용한다.

성공 판정에는 아래를 함께 사용한다.

- 선택 방식·모드·이유 코드·규칙 버전 1과 최종 파일 내용·상태·종료 코드
- 작성자 노드 진입 수, `aiAttempts`, 실제 `codex exec` 실행 수
- 재개 전후 저장된 호출 결과 파일의 내용과 누적 사용량 불변
- 승인 전 재개는 계속 대기하고, 승인 후·완료 후 재개는 추가 AI 실행 없이 종료

## 기본 테스트와 호출 제한

[실행 보조 코드](../src/test/java/io/ohmyluke/cli/WorkflowCliProcess.java)는 임시 PATH의 `codex` 래퍼에서 `exec` 시작을 센다. 실제 호출을 켜도 원자적 표식을 사용해 **시나리오당 첫 `exec`만 실제 실행기에 전달**한다. 두 번째 시도가 생기면 차단하되 시도 수에는 남겨 테스트를 실패시킨다. 응답이나 토큰을 만들어 넣지 않는다.

실제 호출을 끈 기본 경로는 진짜 실행기로 전달하지 않는다. 수정 경로를 일부러 실행해 차단·`BLOCKED`·재개 후 추가 실행 없음도 검사한다. [WorkflowCliProcessTest](../src/test/java/io/ohmyluke/cli/WorkflowCliProcessTest.java)는 가짜 실행 파일로 첫 호출만 전달되는지 확인한다.

[StartCliTest](../src/test/java/io/ohmyluke/cli/StartCliTest.java)는 가짜 AI로 실패 후 성공, 시도 한도, 동일 실패 반복 한도, 한도 도달 후 재개해도 추가 AI 호출 없음, 취소·EOF·잘못된 입력 시 호출 0회를 검사한다. 실제 AI를 반복 호출해 이 제어 규칙을 시험하지 않는다.

## 다시 실행하기

Java 21과 공식 Codex CLI가 있는 macOS/Linux에서 저장소 루트로 실행한다. 실제 경로는 저장된 **ChatGPT 로그인**인지 먼저 확인하며 API 키 인증이면 실패한다. 모델·추론 강도는 사용자 설정을 상속하고 변경하지 않는다.

```bash
# 실제 AI 없이 제어 흐름과 호출 차단 확인
OML_CODEX_INTEGRATION=false OML_WORKFLOW_CODEX_INTEGRATION=false \
OML_START_CODEX_INTEGRATION=false ./gradlew test \
  --tests io.ohmyluke.cli.StartCodexIntegrationTest \
  --tests io.ohmyluke.cli.StartCliTest \
  --tests io.ohmyluke.cli.WorkflowCliProcessTest --rerun-tasks

# 실제 계정 사용량을 쓰는 3개 시나리오도 실행
codex --version
codex login status
OML_CODEX_INTEGRATION=false OML_WORKFLOW_CODEX_INTEGRATION=false \
OML_START_CODEX_INTEGRATION=true ./gradlew test \
  --tests io.ohmyluke.cli.StartCodexIntegrationTest --rerun-tasks
```

`--rerun-tasks`는 비활성화된 이전 결과의 재사용을 막는다. 실제 AI는 시나리오당 1회, 전체 최대 3회이며 각 작업의 시간 한도는 승인 대기를 포함해 5분이다. `maxUsage: 0`으로 사용량 상한은 끄고 호출 수·시간으로 제한한다. **한 호출의 토큰 상한을 보장하지는 않는다.**

일반 테스트·CI에는 세 실제 AI 플래그를 켜거나 개인 로그인·비밀정보를 추가하지 않는다. 성공한 임시 폴더는 JUnit이 정리하고 실패한 폴더는 진단용으로 보존한다. 전체 임시 폴더·인증 파일·개인 설정·원시 제공자 stderr는 커밋하지 않는다.

## 사용량 기록의 의미

JUnit XML `build/test-results/test/TEST-io.ohmyluke.cli.StartCodexIntegrationTest.xml`의 `START_EVIDENCE` 줄에 버전·선택 시나리오·상태·시도/실행 수·토큰을 남긴다. 다음 테스트가 덮어쓰므로 실행 직후 요약을 확인한다.

- 출처는 Codex JSONL의 `turn.completed.usage`를 저장한 결과다. 전체 기록 토큰은 **입력+출력**이며 캐시 입력·추론 출력은 하위 항목이므로 다시 더하지 않는다.
- 사용량이 없으면 0으로 추정하지 않는다. `available=false`를 기록하고 흐름 검증과 별도로 사용량 단언을 실패시킨다.
- `aiAttempts`는 OML 작성자 시도 수, 래퍼 기록은 `codex exec` 시작 수다. Codex 내부 모델 요청 수, 구독 한도 소진율 또는 요금이 아니다.
- 모델·추론은 `inherit`이며 실제 적용 값은 관측하지 않는다. 특정 모델의 품질·성능 결과로 표현하지 않는다.

## 실제 실행 기록: 2026-09-03

21:42:31 KST에 시작했다. macOS 15.7.4 arm64, Java 21.0.8, `codex-cli 0.153.0`, 저장된 ChatGPT 로그인에서 **기본 4개와 실제 3개, 총 7개 통과·실패 0·건너뜀 0**이었다. 모델·추론은 `inherit`이며 실제 적용 값은 관측하지 않았다.

| 실제 시나리오 | 최종 결과 | AI 시도 / Codex 실행 | 입력 | 캐시 입력 | 출력 | 추론 출력 | 전체 기록 |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Auto Direct | `SUCCEEDED` | 1 / 1 | 17,631 | 7,040 | 17 | 0 | 17,648 |
| 수동 Loop | `SUCCEEDED` | 1 / 1 | 17,187 | 7,040 | 17 | 0 | 17,204 |
| Auto 적용 승인 | `SUCCEEDED` | 1 / 1 | 17,187 | 7,040 | 17 | 0 | 17,204 |

`START_EVIDENCE`의 실제 호출 3회 합은 **52,056 기록 토큰**이다. 세 응답 모두 `available=true`, 출처 `codex-exec-jsonl`이었다. 확인·승인·재개로 추가된 Codex 실행은 **0회**이며, 저장 선택·호출 결과 내용·누적 사용량 불변과 승인 전 원본 보존을 자동 단언으로 확인했다. 기본 CHECK 전용 사례는 작성자 진입·Codex 실행·기록 사용량이 모두 0이었다.

이전 [Workflow 검증 기록](workflow-verification.md)은 다른 버전·입력·경로의 관측치다. 두 기록의 토큰 차이를 버전 개선이나 OML 절감 효과로 해석하지 않는다. 이번 결과도 제품 코드를 바꾸어 절감한 결과가 아니라 기존 연결이 작은 사례를 통과했다는 근거다.

이후 실제 AI를 모두 끈 전체 빌드도 통과했다. JUnit XML 기준 **325개 중 317개 통과·8개 건너뜀·실패 0**이다. 건너뜀은 실제 AI 조건부 테스트 4개와 이 macOS에서 실행하지 않는 Linux 전용 4개다. 비활성화된 매개변수 테스트는 시나리오별이 아닌 메서드 1개로 집계된다.

```bash
OML_CODEX_INTEGRATION=false OML_WORKFLOW_CODEX_INTEGRATION=false \
OML_START_CODEX_INTEGRATION=false ./gradlew clean build --offline
```

## 확인 범위와 다음 실험

수동 경로는 `--mode loop`로 실행하므로 실제 콘솔 메뉴 조작을 재현하는 테스트가 아니다. 메뉴는 기존 입력 테스트로 확인한다. 실제 Loop도 최대 1회여서 여러 번 수정하며 성공하는 모델 능력은 확인하지 않는다. 승인 대기 후 정상 종료·재개를 확인하며, AI 실행 도중 강제 종료의 정확히 한 번 실행을 보장하는 증거는 아니다.

이 단계의 질문은 “연결과 기록이 맞는가?”다. “OML을 쓰면 더 적은 토큰으로 잘 푸는가?”는 [별도 비교 실험](experiment.md)에서 같은 과제·시작 코드·모델·추론·시간·외부 기능을 맞추고, 상대 실행의 작업공간·상태·결과만 서로 못 보게 한 뒤 판단한다. 이번에는 비교 대상이 없으므로 절감률·구독 비용 절약을 주장하지 않는다.

공식 [저장 로그인](https://developers.openai.com/codex/auth)과 [비대화형 실행·JSONL](https://developers.openai.com/codex/noninteractive)을 확인해 기존 어댑터의 `--json --ephemeral --sandbox read-only` 경로를 유지한다. 읽기 격리의 한계는 [인증 문서](runtime-auth.md#codex-cli-연결)를 따른다.
