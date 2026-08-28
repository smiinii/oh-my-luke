# Oh My Luke

Oh My Luke(OML)는 사용자가 이미 로그인한 AI CLI를 활용해 개발 작업의 실행, 검증, 반복, 분기, 재개를 관리하는 Java 기반 로컬 AI 워크플로 런타임입니다.

> 쓰던 AI는 그대로, 복잡한 작업은 더 안전하고 끝까지.

## 현재 상태

프로젝트 초기 단계입니다. AI 없이 동작하는 그래프 커널과 로컬 상태 저장·재개 슬라이스를 구현했습니다.

```text
A → 검사
    ├─ PASS → END
    └─ FAIL → A
```

실행 상태는 프로젝트의 `.oml/runs/<run-id>/` 아래에 저장됩니다.

- `state.json`: 버전과 그래프 서명이 포함된 원자적 체크포인트
- `events.jsonl`: 노드 시작·완료를 남기는 추가 전용 이벤트 기록
- `handoff.md`: 다음 실행이나 에이전트가 읽을 인수인계 노트

노드 실행 중 프로세스가 종료되면 `NODE_STARTED` 상태가 남습니다. 재개 시 마지막 안전 체크포인트의 현재 노드를 다시 실행하며, 저장 당시와 그래프 구조가 다르면 실행을 거부합니다.

## 기술 기준

- Java 21 LTS
- Gradle Kotlin DSL
- JUnit 5
- 로컬 우선 CLI
- 단일 그래프 커널
- Spring Boot 미사용

## 실행

시스템에 Gradle을 별도로 설치할 필요가 없습니다.

```bash
./gradlew test
./gradlew run
```

Windows:

```powershell
.\gradlew.bat test
.\gradlew.bat run
```

개발 중인 상태 명령은 다음과 같습니다.

```bash
./gradlew run --args="inspect <run-id>"
./gradlew run --args="cancel <run-id>"
./gradlew run --args="resume <run-id>"
```

`inspect`와 `cancel`은 저장 파일만으로 동작합니다. `resume`은 실행 가능한 노드 코드를 다시 연결해야 하므로 현재는 Java API의 `GraphResolver`를 통해 그래프가 등록된 환경에서 동작합니다. 독립 실행형 CLI의 그래프 로딩은 이후 프리셋 단계에서 연결합니다.

## 배포 계획

현재는 소스에서 빌드하는 개발 단계다. 첫 공개 버전은 OML 자체 실행을 위해 Java나 Node.js를 별도로 설치하지 않도록 OML 전용 Java 런타임을 포함한 운영체제별 패키지로 제공한다. 연결할 AI CLI의 설치와 로그인은 해당 실행기의 요구사항을 따른다.

- GitHub Releases를 배포 파일의 기준 저장소로 사용한다.
- 검증 후 macOS는 Homebrew, Windows는 WinGet 설치를 제공한다.
- Linux는 우선 GitHub Releases에서 설치 파일을 제공한다.
- npm 배포는 계획하지 않는다.
- 실제 설치 테스트를 통과한 운영체제만 지원 대상으로 표시한다.

## 문서

- [제품 범위](docs/product.md)
- [아키텍처](docs/architecture.md)
- [보안 원칙](docs/security.md)
- [AI 실행기와 인증](docs/runtime-auth.md)
- [실험 계획](docs/experiment.md)
- [아키텍처 결정 기록](docs/adr/)
