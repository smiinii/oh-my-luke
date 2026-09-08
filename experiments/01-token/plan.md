# 실험 1 준비와 실행 안내

목표는 Codex CLI·OMX·OML의 같은 개발 작업 성공률과 기록 토큰을 비교하는 것이다. 현재 PR은 **실제 AI 호출 없는 준비 도구**이며 실제 모델 성능 결과가 아니다.

## 진행 순서

1. [#32 준비](https://github.com/smiinii/oh-my-luke/issues/32): 과제, 평가기, 로컬 격리, 계측 샘플, 가짜 실행.
2. [#37 제품 선행 작업](https://github.com/smiinii/oh-my-luke/issues/37): 신규 파일·다중 파일·고정 빌드 검증 지원.
3. [#35 예비 실험](https://github.com/smiinii/oh-my-luke/issues/35): 별도 연습 과제 × 세 도구, 실제 로그·인증·계측 확인.
4. [#36 본 실험](https://github.com/smiinii/oh-my-luke/issues/36): 예비 결과를 보고 규모 확정 후 비교·보고.

## 읽는 순서

- [현재 적합성 및 남은 조건](feasibility.md)
- [과제·완료 조건](tasks.md)
- [격리·토큰·집계 계약](measurement.md)

## 개발 환경

Java 21, Git, Python 3.9 이상과 macOS Seatbelt 또는 Linux bubblewrap이 필요하다. Python은 저장소의 실험 자동화 도구이며 OML 배포 패키지에 포함하지 않는다. 제품 본체와 실험 과제는 Java다. Python 외부 패키지는 없다.

macOS에서는 설치된 JDK 21을 자동 탐색한다. 다른 환경에서는 `JAVA_HOME` 또는 실험 전용 `OML_BENCH_JAVA_HOME`으로 JDK 21을 지정한다. IntelliJ와 다른 프로젝트 설정은 변경하지 않는다.

Linux는 `bwrap`과 unprivileged user namespace가 필요하다. CI는 ubuntu-24.04와 macos-15에서 검사한다. 샌드박스가 실행되지 않으면 일반 프로세스로 우회하지 않는다.

## 실제 AI 없이 검증하기

저장소 루트에서 실행한다.

```bash
PYTHONPATH=experiments/01-token/runner python3 -m unittest discover -s experiments/01-token/tests -v
python3 experiments/01-token/runner/bench.py preflight
```

드라이런은 존재하지 않는 출력 디렉터리를 받는다. 같은 경로를 다시 사용하면 이전 기록을 덮어쓰지 않고 실패한다.

```bash
python3 experiments/01-token/runner/bench.py dry-run experiments/01-token/local-runs/demo-001 --task a
python3 experiments/01-token/runner/bench.py report experiments/01-token/local-runs/demo-001
```

`--task a|b|c|pilot`, `--mode success|fail|timeout|environment_error`를 지원한다. 실제 AI 실행 명령은 제공하지 않으므로 명령을 잘못 선택해 구독 사용량을 소비하지 않는다.

## 출력 구조

```text
demo-001/
├── manifest.json            시작 커밋·과제·도구 해시·실행 순서
├── results.json             모든 실행의 판정·토큰·시간
├── summary.csv / report.md  동일 원시 값에서 재생성
└── a-1-codex/               omx·oml도 별도 공간
    ├── baseline.json        원본 파일 해시
    ├── stdout.jsonl / stderr.txt
    ├── usage-manifest.json / result.json
    └── worker/workspace/    독립 .git과 시작 코드
```

현재 codex/omx/oml 이름은 **가짜 실행의 비교 칸**이다. 세 실제 도구를 호출했다는 뜻이 아니다. 모든 테스트 수치에 `synthetic: true`, `actualAiCalls: 0`을 기록한다.

## 실제 실험 진입 기준

`preflight`는 현재 `liveReady: false`를 반환한다. #37 및 실제 실행기·전체 세션 계측·원격 자료 접근 검증이 완료되어야 #35를 실행할 수 있다. 가짜 로그에서 통과한 계측을 실제 OMX 로그에서도 정확하다고 주장하지 않는다.

모델·추론·각 도구 모드·공통 도구 설정은 예비 실행 전에 고정한다. 본 실험 초안은 3과제 × 3도구 × 3회 = 27회다. 예비 실험의 실제 사용량과 시간을 확인한 후 실행 규모를 확정한다.
