# Workflow 실제 Codex 연결 검증

정적 Workflow가 실제 제공자의 수정 제안을 저장하고, 프로세스가 종료된 뒤에도 승인·재개와 안전 중단을 지키는지 확인한다. [이슈 #18](https://github.com/smiinii/oh-my-luke/issues/18)의 검증이며 새 제품 기능이나 토큰 절감 벤치마크가 아니다.

## 무엇을 어떻게 검사하나

[WorkflowCodexIntegrationTest](../src/test/java/io/ohmyluke/cli/WorkflowCodexIntegrationTest.java)는 임시 프로젝트에 `hello.txt`와 작업 선언만 만든다. 선검사→필요한 경우 EDIT→적용 승인→최종 검사를 연결하며 EDIT는 Direct·최대 1회다.

| 시나리오 | 기대 결과 | 실제 Codex 실행 |
| --- | --- | ---: |
| 파일이 이미 조건을 충족 | `SUCCEEDED`, 파일 불변 | 0회 |
| 수정 제안을 승인 | 대기 중 원본 보존→결정 저장→재개 후 적용·검증 성공 | 1회 |
| 수정 제안을 거부 | `BLOCKED / approval-denied`, 원본 보존 | 1회 |
| 대기 중 사람이 파일을 변경 | 승인해도 `BLOCKED / file-apply-blocked-or-conflict`, 외부 변경 보존 | 1회 |

`workflow`, `inspect`, `approve/deny`, `resume`마다 **실제 OML 진입점을 새 JVM으로 실행**한다. 승인 대기는 종료 코드 3, 결정 저장은 0이며 작업 성공이 아니다. 원본 선언 JSON을 바꾼 뒤에도 저장된 실행으로 재개하는지 검사한다.

[테스트 실행 보조 코드](../src/test/java/io/ohmyluke/cli/WorkflowCliProcess.java)는 임시 PATH의 `codex` 래퍼에서 `exec` 시작만 세고 실제 실행 파일에 인자·표준입력을 그대로 넘긴다. AI 응답을 만들거나 사용량을 보충하지 않는다. 기본 0회 테스트의 래퍼는 실제 Codex를 실행할 수 없게 막는다. 별도 기본 테스트는 일부러 수정 경로에 진입해 이 차단 장치가 실행을 기록하고 거부하는지 확인한다.

재개 전후 writer 전이 수, `aiAttempts`, 실행 횟수, 저장된 호출 결과의 바이트와 누적 사용량이 유지되는지도 비교한다. 호출 결과 파일 수만으로 재호출 여부를 판단하지 않는다.

## 다시 실행하기

개발용 Java 21과 공식 Codex CLI가 필요하다. macOS/Linux에서 작은 임시 프로젝트만 사용한다. 이 테스트는 저장된 **ChatGPT 로그인**을 확인하며 API 키 인증이면 실제 호출 전에 실패한다. 사용자 모델·추론 설정을 상속하고 바꾸지 않는다.

```bash
# 저장소 루트: 실제 AI 없이 기본 경로 확인
./gradlew test --tests io.ohmyluke.cli.WorkflowCodexIntegrationTest

# 로그인 방식 확인 후, 실제 계정 사용량을 쓰는 3개 시나리오 실행
codex --version
codex login status
OML_WORKFLOW_CODEX_INTEGRATION=true ./gradlew test \
  --tests io.ohmyluke.cli.WorkflowCodexIntegrationTest --rerun-tasks
```

`--rerun-tasks`는 이전의 비활성화 결과가 Gradle의 최신 결과로 재사용되는 일을 피한다. 다른 실제 AI 테스트의 `OML_CODEX_INTEGRATION`은 켜지 않는다. 작업 시간은 시나리오당 5분이며 승인 대기도 포함한다. 사용량 상한은 끄고 호출 횟수·시간으로 제한하므로 한 호출의 토큰 상한을 보장하지는 않는다.

일반 CI에는 로그인·비밀정보·실제 AI 실행 설정을 추가하지 않는다. 성공한 임시 폴더는 JUnit이 정리하고 실패 시에는 진단을 위해 보존한다. 인증 파일, 개인 설정, 원시 제공자 stderr나 전체 임시 폴더를 PR에 올리지 않는다.

## 사용량 해석

- JUnit XML `build/test-results/test/TEST-io.ohmyluke.cli.WorkflowCodexIntegrationTest.xml`의 `WORKFLOW_EVIDENCE` 줄에 안전한 측정 요약을 남긴다. 다음 테스트 실행이 덮어쓰므로 필요한 요약을 실행 직후 확인한다.
- 토큰 출처는 저장된 Codex 결과의 `turn.completed.usage`다. 전체 기록 토큰은 **입력+출력**, 캐시 입력·추론 출력은 각각 하위 항목이다.
- `aiAttempts`는 OML 작성자 시도 수, 래퍼 기록은 `codex exec` 프로세스 실행 수다. Codex 내부 모델 요청 횟수·구독 한도 소진율·금액을 뜻하지 않는다.
- 사용량이 누락되면 0토큰으로 추정하지 않는다. 흐름 결과와 별도로 `available=false`를 기록하고 사용량 검증은 실패시킨다.
- 모델·추론은 `inherit`이며 실제 적용 값은 현재 어댑터가 관측하지 않는다. 특정 모델의 성능 결과로 표현하지 않는다.

## 실제 실행 기록: 2026-09-03

15:43 KST, macOS 15.7.4 arm64, Java 21.0.8, `codex-cli 0.149.1`, 저장된 ChatGPT 로그인에서 실행했다. 당시 0회 경로 1개와 실제 호출 시나리오 3개가 **4개 통과·실패 0·건너뜀 0**이었다. 차단 장치 자체를 확인하는 AI 없는 테스트는 그 뒤 추가했다.

| 시나리오 | 최종 결과 | AI 시도 / Codex 실행 | 입력 | 캐시 입력 | 출력 | 추론 출력 | 전체 기록 |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 이미 충족 | `SUCCEEDED` | 0 / 0 | — | — | — | — | 0 |
| 승인 | `SUCCEEDED` | 1 / 1 | 17,918 | 0 | 18 | 0 | 17,936 |
| 거부 | `BLOCKED` | 1 / 1 | 17,410 | 7,168 | 18 | 0 | 17,428 |
| 외부 변경 | `BLOCKED` | 1 / 1 | 17,408 | 7,168 | 18 | 0 | 17,426 |

실제 호출 3회의 기록 토큰 합은 **52,790**이며 세 응답 모두 사용량이 보고됐다. 이미 충족된 사례는 제공자 응답이 없어 세부 항목을 `—`로 표시했다. 대기·결정 저장·재개·완료 후 재개에서 추가 Codex 실행은 **0회**, writer 재진입과 기록 사용량 증가도 없었다. 캐시 입력은 합계에 다시 더하지 않는다.

승인 전 원본 보존, 승인/거부 결정만으로 파일을 바꾸지 않음, 거부 후 원본 보존, 외부 편집 보존, 최종 상태·종료 코드와 저장 결과 불변을 자동 단언으로 확인했다. 제품 코드의 수정 없이 현재 동작이 이 작은 사례를 통과했다는 근거다. 사용량 크기의 원인이나 절감 효과를 이 결과만으로 단정하지 않는다.

## 확인 범위의 한계

이 검증은 승인 대기에서 정상 종료한 프로세스를 새로 시작하는 사례다. AI 실행 도중 강제 종료되었을 때 외부 요청의 정확히 한 번 실행까지 증명하지 않는다. 기존 `ManagedApprovalTest`의 강제 종료·손상 복구와 역할이 다르다.

문자열 한 파일의 작은 스모크 테스트이며 복잡한 개발 과제의 품질, 모든 운영체제, 고정 명령 검증 전체, 악성 호스트 방어를 대표하지 않는다. 비교 대상도 없으므로 [별도 비교 실험](experiment.md) 없이 토큰 절감률을 주장할 수 없다.

공식 문서의 [저장 로그인](https://developers.openai.com/codex/auth)과 [비대화형 실행·JSONL](https://developers.openai.com/codex/noninteractive)을 확인하여, 새 API 키나 모델 재정의 없이 기존 어댑터의 `--json --ephemeral --sandbox read-only` 경로를 사용했다. 읽기 격리의 범위와 제한은 [보안 문서](security.md)를 따른다.
