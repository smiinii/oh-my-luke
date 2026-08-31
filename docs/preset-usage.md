# 개발용 Direct·Loop 실행

공개 설치판이 아니라 Java 21 기반 개발 빌드를 위한 예시다. 실제 Codex 호출은 사용자의 저장 로그인과 사용량을 이용한다. 작은 별도 프로젝트에서 먼저 확인한다.

## 안전한 작은 예제

저장소 루트에서 빌드한 뒤 예제 두 파일을 빈 임시 폴더로 복사한다.

```bash
./gradlew installDist
OML_BIN="$PWD/build/install/oh-my-luke/bin/omluke"
OML_DEMO_DIR="$(mktemp -d)"
cp examples/presets/task.json examples/presets/hello.txt "$OML_DEMO_DIR/"
cd "$OML_DEMO_DIR"
codex login status
"$OML_BIN" run task.json --run-id demo
"$OML_BIN" inspect demo
```

`task.json`의 `file`은 현재 프로젝트 기준 상대 경로이며 기존 파일이어야 한다. 작업표 자신, `.oml`, `.git`, 비밀 파일, 상위 경로는 수정 대상으로 사용할 수 없다. 절대 경로의 작업표 대신 프로젝트 상대 경로로 실행한다.

`mode`는 `DIRECT` 또는 `LOOP`다. Direct는 `maxAttempts`가 반드시 1이다. Loop는 1~20이며 예제는 3회다. `maxRepeatedFailures`는 같은 검증 실패 또는 같은 제안이 계속되는 횟수 제한이다. 값은 경험적으로 최적화된 추천값이 아니라 MVP 안전 기본 예시다.

## 모델을 사용자가 고르는 방법

```bash
omluke run task.json --model YOUR_MODEL --reasoning low
```

모델을 지정하지 않으면 작업표의 `model`을 사용한다. 작업표도 `null`이면 사용자 Codex 설정을 상속한다. 추론 강도도 같은 우선순위다. 지원하지 않는 모델은 Codex가 실패로 보고하며 OML이 임의로 다른 모델로 바꾸지 않는다. 실제 설치 전에는 위 예제의 `"$OML_BIN"`을 사용한다.

```text
CLI의 실행별 선택 → task.json의 선택 → 사용자 Codex 설정
```

## 검증 선택

`requiredText`는 모두 포함돼야 하고 `forbiddenText`는 하나도 포함되면 안 된다. 단순 문서 수정용으로 적합하며 소프트웨어 테스트를 대체하지 않는다.

기존 테스트를 실행하려면 다음처럼 `validation.command`에 **신뢰하는 고정 실행 파일과 인자 배열**을 넣는다. 셸 문자열은 받지 않는다.

```json
{
  "requiredText": [],
  "forbiddenText": [],
  "command": {
    "executable": "/usr/bin/grep",
    "arguments": ["-q", "OML_READY", "hello.txt"],
    "expectedExitCode": 0,
    "timeoutMillis": 10000
  }
}
```

이는 복사한 파일을 읽는 작은 예제다. Java/JUnit도 사본 안에서 독립적으로 실행 가능한 검증 실행 파일·인자로 연결할 수 있지만, **모든 Gradle/npm 테스트가 즉시 되는 것은 아니다**. 자식 프로세스, 의존성 다운로드, 캐시, 환경 변수 제한을 확인해야 한다. 프로젝트 경로 인자는 가능한 상대 경로를 사용한다. 실행 파일만 사본으로 매핑하며 인자 속 절대 경로를 임의로 고치지 않는다.

검증기 코드 자체와 실행 환경은 사용자가 신뢰하고 고정해야 한다. OML은 실행 중 외부에서 테스트 코드가 바뀌거나, 테스트가 목표를 충분히 검증하는지까지 증명하지 않는다. 수정 대상 파일을 검증 실행 파일 자체로 지정할 수 없다.

## 결과와 재개

```bash
omluke inspect demo
omluke resume demo
omluke cancel demo
```

`runId`를 생략하면 자동 생성하며 호출 전에 출력한다. `resume`은 저장 작업표와 모델 설정을 사용하고 원래 작업표 파일을 다시 읽지 않는다. 기존 실행의 목표·모델·한도를 바꾸려면 새 실행 ID로 시작한다.

`result=SUCCEEDED`만 성공이다. `recordedUsage`는 기록된 입력+출력 사용량이며 `allTokenUsageAvailable=false`이면 모든 호출의 실제 토큰이 확인된 것은 아니다. 0이 보여도 ‘무료’나 ‘토큰 미사용’으로 단정하지 않는다. `maxUsage`를 켜면 수치 미확인 응답은 차단한다.

실행 중 Ctrl+C 등으로 종료했다면 같은 ID로 재개할 수 있다. 저장된 응답/적용 작업은 재사용하되, 제공자 응답 직후 저장 전 중단은 중복 호출 가능성이 있다. `cancel`은 현재 노드 실행 잠금을 빼앗는 실시간 강제 종료 기능이 아니다. 활동 중 실행은 잠금 충돌을 알리며 노드 실행이 멈춘 뒤 취소한다.

한도·검증 실패로 멈추면 먼저 변경된 파일과 `inspect` 결과를 확인한다. 자동 롤백은 하지 않는다. `handoff.md`는 처음의 목표/재개 안내이며 동적 진행 상태는 `state.json`/`inspect`가 기준이다.

## 자동화 검증

```bash
./gradlew clean build
OML_CODEX_INTEGRATION=true ./gradlew test \
  --tests io.ohmyluke.preset.PresetCodexIntegrationTest
```

두 번째 명령만 실제 Codex를 호출하며 임시 테스트 프로젝트 하나를 수정한다. 기본 테스트와 CI는 AI 로그인 없이 동작한다.
