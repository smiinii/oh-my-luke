# 자체 포함 개발 패키지

OML은 Java로 개발하지만, 최종 사용자가 OML을 실행하기 위해 Java를 따로 설치하지 않게 하는 것이 배포 원칙이다. 현재 단계에서는 Java 21 런타임을 포함한 **개발용 앱 이미지와 `tar.gz`**를 macOS·Linux에서 각각 만든다.

이 기능은 패키징 기반의 검증이다. 아직 서명된 공개 릴리스나 설치 프로그램은 아니다.

## 무엇이 들어가는가

`jpackage --type app-image`가 다음 항목을 하나로 묶는다.

- OML 애플리케이션과 런타임 의존성
- OML 전용으로 축소한 Java 21 런타임
- 운영체제별 `omluke` 실행 파일

현재 런타임 루트 모듈은 애플리케이션 JAR과 의존성을 `jdeps`로 분석해 확인한 `java.base`, `java.desktop`, `java.sql`이다. `jlink`가 필요한 전이 모듈을 함께 넣는다. 의존성이 바뀌면 이 목록도 다시 분석해야 한다.

[Oracle `jpackage` 문서](https://docs.oracle.com/en/java/javase/21/docs/specs/man/jpackage.html)에 따라 패키지는 대상 운영체제에서 직접 만든다. macOS에서 Linux 패키지를 만드는 식의 교차 빌드는 하지 않는다. 런타임 축소 원리는 [Oracle `jlink` 문서](https://docs.oracle.com/en/java/javase/21/docs/specs/man/jlink.html)를 따른다.

## 개발 빌드와 산출물

소스에서 패키지를 만들 때는 JDK 21이 필요하다. 저장소 루트에서 실행한다.

```bash
./gradlew packageArchive
```

산출물 이름은 다음 형식이다.

```text
build/package/omluke-<version>-macos-<arch>.tar.gz
build/package/omluke-<version>-linux-<arch>.tar.gz
```

검증까지 한 번에 실행하려면 다음 명령을 쓴다.

```bash
./gradlew verifyPackagedApp
```

검증 결과는 로그의 `PACKAGE_EVIDENCE` 한 줄과 아래 JSON 파일에 남는다. 둘 다 빌드 결과물이므로 Git에는 커밋하지 않는다.

```text
build/package/evidence/package-evidence.json
```

## 무엇을 실제로 검증하는가

검증은 원본 앱 이미지가 아니라 `tar.gz`를 새 임시 폴더에 다시 푼 결과를 사용한다. 운영체제의 `tar`로 실행 권한과 Java 런타임의 심볼릭 링크를 보존하고, 원본과 압축 해제본의 링크가 같은지 비교한다. 파일 시각·소유자·gzip 시각을 정규화하고 Linux는 항목 순서도 정렬한다. CI는 같은 체크아웃에서 패키지를 두 번 만들어 SHA-256이 같은지 운영체제별로 검사한다.

1. 압축 해제한 OML 실행 파일과 포함된 Java가 실행 가능한지 확인한다.
2. 런타임 심볼릭 링크와 `jpackage` 버전 메타데이터를 확인하고, macOS에서는 번들 서명 무결성도 검사한다.
3. `PATH`를 빈 폴더로 바꾸고 `JAVA_HOME` 등 나머지 환경을 제거한다.
4. 도움말을 실행한다.
5. AI 없는 Workflow를 시작해 승인 대기 상태를 확인한다.
6. 별도 프로세스로 승인하고 다시 재개해 Java 검사가 성공하는지 확인한다.
7. AI 시도·사용량이 0인지와 초기 크기 기준을 확인한다.

현재 기준은 다음과 같다.

- `tar.gz`: 50,000,000바이트 이하
- 앱 이미지의 일반 파일 합계: 150,000,000바이트 이하

파일시스템이 실제로 차지하는 디스크 블록은 환경마다 다를 수 있다. JSON의 `imageBytes`는 압축 해제한 일반 파일의 논리적 크기 합계다.

JSON은 Gradle 제품 버전과 운영체제용 네이티브 버전을 별도 필드로 기록한다. macOS `jpackage`가 0으로 시작하는 버전을 거부하므로 `native major = product major + 1`로 변환한다. 예를 들어 제품 `0.1.0-SNAPSHOT`은 네이티브 `1.1.0`이다. 나머지 숫자는 그대로여서 제품 버전이 바뀌면 네이티브 버전도 함께 바뀐다.

2026-09-04 macOS 15.7.4 ARM64 로컬 검증에서는 Java 21.0.8을 포함했고, 압축 파일 약 34.6MB와 앱 이미지 약 51.2MB로 통과했다. 빌드별 정확한 바이트와 SHA-256, 다른 운영체제의 수치는 해당 JSON과 CI artifact를 기준으로 확인한다.

## CI에서 남기는 근거

PR과 `main` CI는 macOS와 Ubuntu에서 공통으로 제공되는 Temurin `21.0.11+10`을 고정해 각자 앱 이미지를 만들고 압축 해제본을 실행한다. 같은 패키지를 한 번 더 만들어 SHA-256도 비교한다. 성공한 개발 패키지와 JSON 근거는 7일 동안 GitHub Actions artifact로 보관한다. 이 artifact는 설치 안정성을 보증하는 GitHub Release가 아니다.

## 사용자 Java와 Codex의 차이

- 압축본 사용자는 **OML을 위해 Java를 설치할 필요가 없다.** Java는 패키지 안에 있다.
- Codex를 실제로 호출하려면 **Codex CLI 설치와 사용자 로그인은 별도**로 필요하다.
- AI 없는 검사·승인·상태 명령은 Codex가 없어도 동작한다.

## 아직 지원한다고 말하지 않는 것

- GitHub Release 공개와 체크섬 배포
- macOS 코드 서명·공증, DMG/PKG, Homebrew
- Linux 배포판별 설치 검증, DEB/RPM
- Windows 앱 이미지·설치 프로그램·WinGet
- 자동 업데이트와 완전한 삭제 절차

패키지가 생성된다는 사실과 OML의 파일·프로세스 기능이 해당 운영체제에서 모두 지원된다는 주장은 다르다. 공개 지원 표시는 설치·첫 실행·기능·삭제까지 실제 환경에서 검증한 뒤 추가한다.
