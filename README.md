# Oh My Luke

Oh My Luke(OML)는 사용자가 이미 로그인한 AI CLI를 활용해 개발 작업의 실행, 검증, 반복, 분기, 재개를 관리하는 Java 기반 로컬 AI 워크플로 런타임입니다.

> 쓰던 AI는 그대로, 복잡한 작업은 더 안전하고 끝까지.

## 현재 상태

프로젝트 초기 단계입니다. 첫 번째 목표는 AI 없이 동작하는 작은 그래프 실행 커널을 만드는 것입니다.

```text
A → 검사
    ├─ PASS → END
    └─ FAIL → A
```

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
