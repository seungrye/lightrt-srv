# 개발 가이드

## 개발 순서 (Spec 주도 + TDD)

기능 추가나 변경 시 반드시 아래 순서를 따른다.

1. **SPEC.md 수정** — 추가/변경할 동작을 명세에 먼저 반영한다.
2. **테스트 작성** — 명세를 근거로 실패하는 테스트를 먼저 작성한다.
3. **구현** — 테스트를 통과시키는 최소한의 코드를 작성한다.
4. **리팩터** — 테스트가 통과된 상태를 유지하면서 정리한다.

명세 없는 구현, 테스트 없는 기능 추가는 하지 않는다.

## 빌드

```bash
# Debug APK 빌드
./gradlew assembleDebug

# 기기에 설치 (연결된 기기 필요)
./gradlew installDebug

# 린트
./gradlew lint
```

## 테스트

```bash
# 단위 테스트 전체 실행
./gradlew test

# 특정 클래스만 실행
./gradlew test --tests "com.litert.tunnel.TunnelServerTest"

# 특정 메서드만 실행
./gradlew test --tests "com.litert.tunnel.TunnelServerTest.GET health returns ok when engine is ready"
```

## 테스트 작성 패턴

**HTTP 엔드포인트 테스트** — `TunnelServer.install()`이 `Application`을 받으므로 실제 소켓 없이 Ktor `testApplication`으로 검증한다.

```kotlin
@Test
fun `POST chat completions returns tokens`() = testApplication {
    val engine = mockk<InferenceEngine>()
    every { engine.isReady } returns true
    every { engine.generate(any()) } returns flowOf("Hello", " world")
    application { TunnelServer(engine) {}.install(this) }

    val response = client.post("/v1/chat/completions") { ... }
    assertEquals(HttpStatusCode.OK, response.status)
}
```

**Flow/StateFlow 테스트** — Turbine의 `turbineScope { }` 또는 `flow.test { }` 사용.

**다운로드 테스트** — OkHttp `MockWebServer`로 HTTP 응답을 시뮬레이션.

**LiteRTEngine** — 실제 SDK는 기기 없이 실행 불가. `InferenceEngine` 인터페이스를 MockK로 대체한다.
