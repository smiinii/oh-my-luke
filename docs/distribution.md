# v0.1.0-rc.1 시험 배포 준비

OML은 Java로 개발하지만 사용자가 OML 자체를 실행하려고 Java나 Node.js를 설치하지 않게 한다. macOS Apple Silicon과 Linux x64용 `v0.1.0-rc.1`을 운영체제별로 검증하고 공개 GitHub prerelease로 게시한다.

PR에서는 쓰기 권한이 없는 드라이런만 실행한다. 공개는 드라이런과 사용자 실기기 확인을 통과한 뒤, `main`의 정확한 40자리 커밋을 입력한 수동 워크플로만 수행한다.

## 배포 묶음의 구성

`jpackage --type app-image`와 축소한 Java 21 런타임을 다음 파일과 하나의 최상위 폴더로 묶는다.

- 운영체제용 `omluke.app` 또는 `omluke` 앱 이미지
- 사용자 영역에 설치하는 `install.sh`
- OML 프로그램만 제거하는 `uninstall.sh`
- 제품 버전을 적은 `VERSION`
- 대상 운영체제와 CPU를 적은 `PLATFORM`
- 첫 실행에 참고할 `examples/`

런타임 루트 모듈은 애플리케이션 JAR과 의존성을 `jdeps`로 분석한 `java.base`, `java.desktop`, `java.sql`이며 `jlink`가 전이 모듈을 함께 넣는다. 의존성이 바뀌면 다시 분석한다.

[Oracle `jpackage` 문서](https://docs.oracle.com/en/java/javase/21/docs/specs/man/jpackage.html)에 따라 대상 운영체제에서 직접 패키징한다. macOS에서 Linux 파일을 만드는 교차 빌드는 하지 않는다. 런타임 축소는 [Oracle `jlink` 문서](https://docs.oracle.com/en/java/javase/21/docs/specs/man/jlink.html)를 따른다.

## 개발 빌드

소스에서 만들 때만 JDK 21이 필요하다.

```bash
./gradlew packageArchive
./gradlew verifyPackagedApp
```

검증이 끝나면 현재 운영체제에 맞는 세 파일이 생긴다.

```text
build/package/omluke-<version>-<os>-<arch>.tar.gz
build/package/omluke-<version>-<os>-<arch>.tar.gz.sha256
build/package/evidence/omluke-<version>-<os>-<arch>.json
```

압축본은 `omluke-<version>-<os>-<arch>/` 하나만 최상위 항목으로 가진다. 절대경로와 `..` 항목을 허용하지 않는다. 심볼릭 링크는 최상위 폴더 안의 실제 항목만 가리켜야 하고 하드 링크는 허용하지 않으며, 실행 권한과 안전한 Java 런타임 링크를 보존한다.

## 자동 검증 범위

검증은 원본 앱 이미지가 아닌 새 임시 폴더에 다시 푼 다운로드 후보를 사용한다.

1. 제품 버전, RC 태그, 파일명, 대상 플랫폼과 앱 메타데이터가 일치하는지 확인한다.
2. SHA-256 sidecar가 실제 압축본과 일치하는지 확인한다.
3. 실행 파일, 포함 Java와 모든 런타임 심볼릭 링크를 확인한다.
4. 빈 `PATH`와 격리된 `HOME`에서 `--help`, `--version`을 실행한다.
5. 사용자 전용 임시 prefix에 설치하고 압축본에 든 예제로 AI 없는 승인 Workflow를 완료한다.
6. 같은 RC를 다시 설치해도 기존 설치가 깨지지 않는지 확인한다.
7. 제거 후 OML 명령과 설치 파일만 없어지는지 확인한다.
8. 별도 프로젝트 파일, `.oml` 실행 기록과 가짜 Codex 사용자 상태가 바이트 단위로 유지되는지 확인한다.
9. 다시 설치한 뒤 기존 실행을 조회할 수 있는지 확인하고 마지막으로 제거한다.
10. 소유 표시가 없는 디렉터리는 제거하지 않는지 확인한다.

빈 `PATH` 검증은 OML 실행에 외부 Java와 Node.js를 사용하지 않았다는 뜻이다. 실제 AI 작업에 필요한 Codex CLI까지 패키지에 들어 있다는 뜻은 아니다.

초기 크기 한도는 다음과 같다.

- `tar.gz`: 50,000,000바이트 이하
- 앱 이미지 일반 파일 합계: 150,000,000바이트 이하

JSON evidence의 `ranWithEmptyPath`와 `bundledJavaVerified`는 실제 위 실행을 통과한 결과다. 파일시스템의 디스크 블록 사용량은 환경마다 달라질 수 있다.

## 재현성과 Release 묶음

파일 시각·소유자·gzip 시각을 정규화하고 Linux는 항목 순서도 정렬한다. 테스트 JVM과 Linux 앱 이미지 생성이 겹치지 않도록 작업 순서도 고정한다. CI는 같은 체크아웃에서 두 번 패키징한 SHA-256이 같은지 각 운영체제에서 확인한다.

드라이런의 마지막 Ubuntu job은 두 플랫폼 artifact를 다시 내려받아 다음을 교차검증한다.

- 정확히 두 운영체제 압축본과 고유 evidence가 있는가
- 각 sidecar, 실제 파일, evidence의 SHA-256이 같은가
- 버전·운영체제·CPU와 외부 Java 없는 실행 근거가 맞는가
- 정렬된 통합 `SHA256SUMS`가 만들어지는가

드라이런 결과는 `omluke-0.1.0-rc.1-release-bundle`이라는 14일짜리 Actions artifact다. 게시 워크플로는 같은 검증을 새로 수행하고 통과한 파일만 공개 prerelease에 영구 첨부한다. Release에는 운영체제별 압축본·개별 sidecar·통합 `SHA256SUMS`·evidence가 함께 올라간다.

## 설치, 전환과 제거 원칙

기본 prefix는 `$HOME/.local`이다.

```text
$HOME/.local/bin/omluke
$HOME/.local/lib/omluke/versions/<version>/
$HOME/.local/lib/omluke/uninstall.sh
```

최초 설치 루트는 소유 표시까지 임시 경로에서 완성한 뒤 게시한다. 설치는 버전별 디렉터리를 만든 다음 `bin/omluke` 심볼릭 링크만 선택한 버전으로 바꾼다. 같은 버전 재설치는 기존의 완전한 설치를 재사용한다. 다른 버전은 이전 버전을 남겨 롤백 가능성을 보존하지만, 실제 서로 다른 RC 사이의 업데이트는 다음 후보가 생긴 뒤 검증한다.

설치와 제거는 같은 prefix의 운영체제 파일 잠금을 사용한다. 다른 수명주기 작업이 진행 중이면 기다리거나 덮어쓰지 않고 실패하고, 프로세스가 강제 종료돼도 커널이 잠금을 자동 해제한다. 잠금 파일 자체는 다음 작업에서 같은 inode를 재사용하도록 남긴다. 입력한 prefix는 끝·반복 슬래시와 기존 심볼릭 링크 구성요소가 없는 절대경로여야 한다. 예를 들어 macOS의 `/tmp` 별칭 대신 실제 경로를 사용한다.

스크립트는 다음 경우 실패 후 중단한다.

- prefix가 상대경로·운영체제 핵심 경로이거나 `.`, `..`, 끝·반복 슬래시를 포함함
- prefix부터 설치 루트까지 기존 경로 구성요소가 심볼릭 링크임
- 패키지의 운영체제·CPU가 현재 기기와 다름
- OML 소유 표시가 없는 기존 설치 루트가 있음
- 기존 `bin/omluke`가 일반 파일이거나 다른 대상을 가리킴
- 설치·제거가 동시에 실행 중이거나 소유 표시·제거 스크립트가 심볼릭 링크임
- 제거할 경로에 정확한 OML 소유 표시가 없음

기본 제거는 설치 루트와 OML이 소유한 명령 링크만 없앤다. 작업 프로젝트의 `.oml`, 이미 수정된 파일, Codex CLI와 로그인은 설치 위치 밖의 사용자 자산이므로 건드리지 않는다.

## 실행을 켜고 끈다는 의미

OML은 로그인 시 시작되거나 계속 실행되는 데몬이 아니다. `omluke start ...` 또는 `resume`을 실행한 동안만 foreground 프로세스로 동작하고 끝나면 종료한다. 승인 대기 상태도 디스크에 저장한 뒤 프로세스가 끝난다.

- 잠시 중단: `Ctrl+C`, 이후 같은 ID로 `resume`
- 상태 확인: `inspect`
- 영구 포기: 실행 프로세스가 끝난 뒤 `cancel`

`cancel`은 일시정지가 아니고 활동 중인 노드를 즉시 강제 종료하지도 않는다.

## CI와 공개 경계

드라이런은 고정된 `macos-15` ARM64와 `ubuntu-24.04` x64, Temurin `21.0.12+8.0.LTS`에서 실행한다. 워크플로 전체 권한은 `contents: read`뿐이며 Release API 호출과 태그 생성 단계가 없다. 따라서 PR이나 드라이런만으로 Release를 공개할 수 없다.

게시 워크플로는 수동 실행만 허용한다. 입력 커밋이 40자리 SHA이고 현재 `main`의 HEAD이며 제품 버전이 RC와 같은지 두 운영체제 job에서 확인한다. 모든 패키지 검증이 통과한 뒤 publish job 하나에만 `contents: write`를 주고, 기존 태그나 Release가 있으면 덮어쓰지 않고 중단한다.

## 아직 지원한다고 말하지 않는 것

- 안정판과 장기 지원 약속
- macOS Developer ID 서명·공증, DMG/PKG
- 서로 다른 버전 사이의 업데이트·롤백 호환성
- Linux 배포판별 호환성, DEB/RPM
- Windows, Intel Mac, WinGet
- 자동 업데이트와 프로젝트 `.oml` 완전 삭제 명령

현재 네이티브 앱 버전은 RC 접미사를 표현하지 못해 `0.1.0-rc.1`과 향후 `0.1.0`이 같은 `1.1.0`으로 매핑된다. 사용자 영역의 버전 디렉터리는 제품 버전 전체로 구분하지만, 정식 서명·공증 및 안정판 전에 네이티브 버전 정책을 별도로 확정해야 한다.

macOS의 현재 번들 무결성 검사는 Developer ID 서명·공증을 대신하지 않는다. 체크섬도 파일 손상 탐지 수단이지 게시자 신원 증명은 아니다. prerelease의 실제 설치 결과를 더 수집한 뒤에만 안정판 지원 범위를 정한다.
