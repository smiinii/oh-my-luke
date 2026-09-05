# 14. Homebrew 한 줄 설치

## 무엇을 만들었나

사용자가 `brew install smiinii/tap/oh-my-luke` 한 줄로 OML 공개 시험판을 설치하는 별도 tap을 만들었다. OML용 Java는 패키지 안에 있으며 Homebrew는 `omluke` 명령을 연결하고 업데이트·제거를 관리한다.

## 어떤 순서로 처리되나

```text
완전히 지정한 Formula 설치 요청
→ OML Formula 하나만 신뢰
→ 운영체제·CPU 지원 여부 확인
→ 공개 Release의 고정 URL 다운로드
→ SHA-256 일치 확인
→ 자체 포함 runtime을 Homebrew Cellar에 설치
→ omluke 실행 wrapper 연결
```

Formula는 macOS Apple Silicon과 Linux x64에 서로 다른 Release asset을 선택한다. 지원하지 않는 CPU는 다운로드·설치 전에 중단한다. OML의 사용자용 `install.sh`는 실행하지 않으므로 `$HOME/.local` 수동 설치와 Homebrew 설치 경로를 섞지 않는다.

## 왜 필요한가

GitHub Release의 압축본은 체크섬 확인·압축 해제·PATH 설정을 사용자가 직접 해야 한다. Homebrew는 이 과정을 한 명령으로 줄이고 이후 버전 확인, 업데이트와 제거를 같은 도구로 관리한다.

## 무엇으로 검증하나

- 공개 source와 운영체제별 runtime의 SHA-256을 Formula에 고정한다.
- `brew style`과 `brew audit --strict --online`을 통과한다.
- 깨끗한 macOS CI에서 Formula를 실제 설치하고 `brew test`로 `--version`과 `--help`를 실행한다.
- 마지막으로 사용자 실기기에서 설치·실행·업데이트 확인·제거를 검증한다.

## 아직 지원하지 않는 것

- 공식 `homebrew/core` 등록과 `brew install oh-my-luke`만으로 시작하는 설치
- macOS Developer ID 서명·공증
- Windows와 Intel Mac
- 서로 다른 공개 버전 사이의 실제 업그레이드·롤백 검증

Homebrew는 설치 편의와 체크섬 확인을 제공하지만 Apple의 서명·공증을 대신하지 않는다.
