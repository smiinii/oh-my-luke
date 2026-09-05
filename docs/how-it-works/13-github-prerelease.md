# 13. 검증된 GitHub prerelease 게시

## 무엇을 만들었나

`v0.1.0-rc.1`을 공개 GitHub prerelease로 게시하는 수동 자동화를 만들었다. macOS Apple Silicon과 Linux x64 패키지, 개별 SHA-256, 통합 `SHA256SUMS`, CI 검증 근거를 한 Release에서 제공한다.

## 어떤 순서로 처리되나

```text
게시할 main 커밋을 40자리 SHA로 입력
→ 입력 SHA가 현재 main HEAD이고 버전이 RC와 같은지 확인
→ macOS와 Linux에서 각각 새로 빌드·실행·설치·제거 검증
→ 같은 환경에서 다시 만들어 SHA-256 재현성 확인
→ 두 운영체제 결과와 evidence 교차검증
→ 기존 태그·Release가 없는 경우에만 prerelease 생성
```

PR의 드라이런은 계속 읽기 권한만 사용한다. 실제 게시 job 하나만 모든 검증이 끝난 뒤 `contents: write`를 얻으므로, 코드 변경이나 PR만으로 외부 Release가 생기지 않는다. 이미 같은 태그나 Release가 있으면 자동으로 덮어쓰지 않는다.

## 왜 필요한가

Actions artifact는 로그인해야 받을 수 있고 보관 기간이 끝나면 사라진다. 공개 prerelease는 사용자가 고정된 URL에서 동일한 검증 파일을 내려받고 체크섬을 확인하게 한다. 게시 대상을 현재 `main`의 정확한 커밋으로 제한해, 검토하지 않은 브랜치나 오래된 커밋이 공식 후보가 되는 것도 막는다.

## 무엇으로 검증하나

- 기존 일반·패키지 통합 테스트와 설치 수명주기 검사를 두 운영체제에서 다시 실행한다.
- archive를 두 번 만들고 SHA-256이 같은지 확인한다.
- 조립 스크립트가 플랫폼·버전·체크섬·evidence를 교차검증한다.
- 게시 후 공개 asset을 새 폴더에 다시 내려받아 통합·개별 체크섬과 `--version`을 확인한다.

## 아직 지원하지 않는 것

- `v0.1.0` 안정판과 장기 호환성 약속
- macOS Developer ID 서명·공증
- Windows, Intel Mac과 검증하지 않은 Linux 배포판
- 서로 다른 RC 사이의 자동 업데이트·롤백 검증

체크섬과 재현 빌드는 손상·불일치를 찾는 근거이며 Apple의 게시자 신원 확인을 대신하지 않는다.
