# 공통 과제 규칙

- Java 21, 원 단위 long. 상품 금액/단가는 0~1,000,000,000원, 수량은 1~99를 평가 범위로 한다.
- 할인은 0~Long.MAX_VALUE와 음수 거부를 확인한다. 이 범위에서 long 산술 오버플로는 없다.
- 공개 API와 기존 파일을 삭제하지 않는다. build.gradle.kts, settings.gradle.kts, Wrapper, 이 문서와 PublicChecks.java는 변경하지 않는다.
- 수정할 수 있는 파일은 src/main/java/benchmark/order/*.java, 추가 가능한 테스트는 src/test/java/benchmark/order/*Checks.java다.
- 추가 테스트는 public static void main(String[] args)를 가지며 틀린 결과에서 AssertionError 등으로 실패해야 한다.
- PublicChecks.equal(expected, actual)을 사용할 수 있다. 기존 PublicChecks를 수정하지 말고 별도 테스트를 추가한다.
- ./gradlew test가 기존 및 추가 *Checks를 실행한다. 외부 의존성을 추가하지 않는다.
- 테스트 코드는 평가 대상으로 직접 제공한 원본/기준 구현을 탐색하지 않고 공개 메서드의 동작을 검사해야 한다.
- 심볼릭 링크·하드링크·특수 파일 금지. 파일 500개, 단일 2 MB, 합계 16 MB, 트리 항목 2,000개, 깊이 32 이내로 제한한다. 최종 평가의 heap 256 MiB·metaspace 128 MiB·전체 120초 한도는 세 도구에 동일하게 적용한다.
