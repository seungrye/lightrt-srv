# LiteRT Tunnel — 명세서

LiteRT Tunnel은 Google LiteRT-LM SDK를 통해 로컬 LLM을 로드하고,
`localhost:8080`에서 OpenAI 호환 HTTP API로 노출하는 Android 앱입니다.

---

## 1. 컴포넌트

### 1.1 LiteRTEngine

LiteRT-LM SDK를 감싸는 래퍼. 테스트에서 가짜 구현으로 대체할 수 있도록 인터페이스로 추상화합니다.

```
interface InferenceEngine {
    val isReady: Boolean
    val backendName: String                         // "GPU" | "CPU"
    suspend fun initialize(config: EngineConfig): Boolean
    fun generate(messages: List<Message>): Flow<String>
    fun clearHistory()
    fun shutdown()
}
```

**동작:**
- `initialize`는 GPU를 먼저 시도하고, 실패 시 CPU로 자동 폴백합니다.
- `generate`는 전체 메시지 목록(`system`, `user`, `assistant` 역할)을 받습니다.
  - 시스템 메시지는 대화의 `systemInstruction`으로 적용됩니다.
  - 시스템 메시지가 없으면 기본값을 사용합니다.
  - 새 사용자 메시지를 전송하기 전에 user/assistant 턴을 순서대로 재생합니다.
- `clearHistory`는 대화 객체를 초기화하되 엔진은 유지합니다.
- `shutdown`은 모든 네이티브 리소스를 해제합니다.

---

### 1.2 TunnelRepository

싱글톤 상태 허브. `TunnelService`(쓰기)와 `MainViewModel`(읽기) 모두 이를 사용합니다.
BroadcastReceiver 불필요.

```
enum class TunnelStatus { IDLE, LOADING, RUNNING, ERROR, STOPPED }

data class TunnelState(
    val status: TunnelStatus = TunnelStatus.IDLE,
    val port: Int = 8080,
    val backendName: String = "",
    val modelName: String = "",
    val error: String? = null,
    val requestCount: Long = 0,
    val recentLogs: List<RequestLog> = emptyList(),  // 최근 100개
)
```

**상태 전이:**
```
IDLE ──start()──► LOADING ──성공──► RUNNING
                          └──실패──► ERROR
RUNNING ──stop()──► STOPPED ──start()──► LOADING
ERROR   ──start()──► LOADING
```

**메서드:**
- `fun start(modelPath: String, useGpu: Boolean)` — `TunnelService` 실행
- `fun stop()` — 서비스 중지
- `fun appendLog(log: RequestLog)` — 요청마다 `TunnelServer`가 호출

---

### 1.3 TunnelServer

Ktor CIO HTTP 서버. 완전히 주입 가능(`InferenceEngine` 인터페이스를 받음).

**엔드포인트:**

| 메서드 | 경로 | 설명 |
|--------|------|------|
| GET | `/health` | 서버 활성 상태 + 엔진 준비 상태 확인 |
| GET | `/v1/models` | OpenAI 호환 모델 목록 |
| POST | `/v1/chat/completions` | 채팅 완성 (스트리밍 + 비스트리밍) |
| POST | `/reset` | 대화 기록 초기화 |

**POST /v1/chat/completions 요청 예시:**
```json
{
  "model": "any-string",
  "messages": [
    {"role": "system", "content": "You are helpful."},
    {"role": "user",   "content": "Hello"}
  ],
  "stream": false,
  "max_tokens": 512,
  "temperature": 0.7
}
```

**동작:**
- 엔진 미준비 시 `503`과 `{"error": "...", "code": 503}` 반환.
- `stream: true` → SSE (`text/event-stream`), 토큰마다 `data: {...}\n\n`, 종료 시 `data: [DONE]\n\n`.
- `stream: false` → 단일 JSON 응답.
- 요청의 알 수 없는 필드는 무시(`ignoreUnknownKeys = true`).
- 포트 8080→8082 순서로 바인딩 시도; 바인딩된 포트를 반환.

---

### 1.4 GgufEngine

llama.cpp를 JNI로 래핑하여 `.gguf` 모델 파일을 구동하는 `InferenceEngine` 구현체.

**JNI 레이어 (`LlamaJni`):**
```kotlin
object LlamaJni {
    external fun nativeInit(modelPath: String, nThreads: Int, useVulkan: Boolean, nCtx: Int): Long
    external fun nativeGetChatTemplate(handle: Long): String  // GGUF 메타데이터에서 읽음 (로깅용)
    external fun nativeGenerate(handle: Long, roles: Array<String>, contents: Array<String>, callback: (String) -> Unit)
    external fun nativeClearContext(handle: Long)
    external fun nativeFree(handle: Long)
}
```

**JNI 시그니처:**
```kotlin
fun nativeGenerate(
    handle: Long,
    roles: Array<String>,
    contents: Array<String>,
    toolCallsJsonPerMsg: Array<String>,  // 메시지별 tool_calls JSON; 없으면 ""
    toolCallIds: Array<String>,          // 메시지별 tool_call_id; 없으면 ""
    toolsJson: String,                   // 툴 선언 JSON 배열; 없으면 ""
    enableThinking: Boolean,
    callback: TokenCallback,
)
```

**채팅 템플릿 처리:**
- 템플릿 적용은 C++ 레이어에서 처리한다.
  - `llama_model_chat_template()`이 반환하는 값은 Jinja2 전체 템플릿이므로, Kotlin에서 키워드 매칭으로 판별하면 오동작한다.
- Gemma 4 (`<|turn>` 감지 시): `build_gemma4_prompt()`로 완전한 템플릿 적용:
  - `tools`: 시스템 턴 내 `<tool>...</tool>` 블록으로 포함
  - `tool_calls` (assistant): `<|call>...</call|>` 블록
  - `tool` 역할 메시지: `<|response>...</response|>` 블록
  - `strip_thinking`: 히스토리 내 assistant 응답에서 `<|channel>thinking<|channel>...<|channel>` 제거
  - `enable_thinking`: 어시스턴트 시작 토큰 앞에 `<|channel>thinking<|channel>` 추가
- 그 외 모델: `llama_chat_apply_template()` → 실패 시 ChatML 폴백.

**동작:**
- `initialize` 시 Vulkan 백엔드 먼저 시도, 실패 시 CPU로 자동 폴백.
- `generate`는 매 호출마다 전체 메시지 목록을 채팅 템플릿에 통과시켜 단일 프롬프트로 만든 뒤 llama_context에 제출한다.
- `clearHistory`는 llama_context를 KV 캐시 포함 초기화(`llama_kv_cache_clear`)한다. 네이티브 모델은 유지.
- `shutdown`은 `llama_context`, `llama_model`을 순서대로 해제한다.
- `maxConversationTurns` / `maxInputChars` / `contextReplayTurns` 동작은 LiteRTEngine과 동일.
- `backendName`은 `"Vulkan"` 또는 `"CPU"` 반환.

---

### 1.5 InferenceEngineFactory

모델 파일 확장자로 적절한 엔진을 생성하는 팩토리.

```kotlin
object InferenceEngineFactory {
    fun create(modelPath: String, context: Context): InferenceEngine =
        when (File(modelPath).extension.lowercase()) {
            "gguf"      -> GgufEngine(context)
            "litertlm"  -> LiteRTEngine(context)
            else        -> throw IllegalArgumentException("Unsupported model format")
        }
}
```

`TunnelService`는 `LiteRTEngine(context)` 직접 생성 대신 `InferenceEngineFactory.create(modelPath, context)`를 호출한다.

---

### 1.6 커스텀 모델 관리 (MainViewModel)

모델 디렉토리를 스캔하여 사용자가 추가한 커스텀 모델 파일을 카드 형식으로 화면에 표시한다.

**동작:**
- `modelsDir`의 `.gguf` / `.litertlm` 파일을 모두 스캔하여 `customModels: StateFlow<List<File>>`로 노출.
- 앱 시작 시, 파일 복사 완료 시, 커스텀 모델 삭제 시 자동 재스캔.
- `ModelsScreen`은 내장 모델 파일명(`BUILT_IN_MODELS.filename`)은 커스텀 목록에서 제외해 표시.
- 파일 피커로 파일을 가져올 때 원본 파일명을 유지한다 (타임스탬프 기반 이름 사용 금지).
  - 같은 이름의 파일이 이미 존재하면 덮어쓴다.
- 커스텀 모델 카드: 파일명(확장자 제외), 포맷 배지(GGUF / LiteRT), 파일 크기, Use / Delete 버튼.

---

### 1.8 ModelDownloader

HTTP를 통해 `.litertlm` 및 `.gguf` 파일을 이어받기 지원과 함께 다운로드합니다.

**동작:**
- 임의의 URL 허용 (HuggingFace에 하드코딩되지 않음).
- `DownloadProgress(progressFraction, downloadedBytes, totalBytes, speedBps, isDone, error)` 방출.
- 목적 파일이 이미 존재하면 `Range: bytes=N-` 헤더 전송 (이어받기).
- 최종 파일 크기를 `minValidBytes`와 비교하여 검증; 불일치 시 삭제 후 예외 발생.
- 경로는 호출자가 `destFile: File`로 지정 — 다운로더 내부에 경로 정책 없음.

---

### 1.9 네트워크 보안

Android 9+는 기본적으로 평문 HTTP를 차단합니다. LiteRT Tunnel은 자체 서버와 `localhost`에서 HTTP로 통신하므로, `res/xml/network_security_config.xml`을 통해 **localhost에 한해서만** 평문 통신을 명시적으로 허용해야 합니다.

외부 트래픽(HuggingFace 다운로드 등)은 HTTPS를 유지해야 합니다 — `localhost` / `127.0.0.1` 외의 도메인에 대해 보안을 완화해서는 안 됩니다.

---

## 2. API 명세 (OpenAI 호환)

### GET /health
```json
{"status": "ok", "model": "<model-name>", "backend": "GPU", "ready": true}
```

### GET /v1/models
```json
{
  "object": "list",
  "data": [{"id": "<model-name>", "object": "model", "created": 0, "owned_by": "local"}]
}
```

### POST /v1/chat/completions (비스트리밍)
```json
{
  "id": "chatcmpl-<timestamp>",
  "object": "chat.completion",
  "created": 1234567890,
  "model": "<model-name>",
  "choices": [{
    "index": 0,
    "message": {"role": "assistant", "content": "..."},
    "finish_reason": "stop"
  }]
}
```

### POST /v1/chat/completions (스트리밍, SSE)
```
data: {"id":"chatcmpl-...","object":"chat.completion.chunk","created":...,"model":"...","choices":[{"index":0,"delta":{"role":"assistant","content":""},"finish_reason":null}]}

data: {"id":"chatcmpl-...","object":"chat.completion.chunk","created":...,"model":"...","choices":[{"index":0,"delta":{"content":"Hello"},"finish_reason":null}]}

data: {"id":"chatcmpl-...","object":"chat.completion.chunk","created":...,"model":"...","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

data: [DONE]
```

---

## 3. 테스트 전략

| 레이어 | 테스트 종류 | 도구 |
|--------|------------|------|
| TunnelRepository | 단위 테스트 | JUnit5 + Turbine |
| TunnelServer | 통합 테스트 | Ktor `testApplication` |
| ModelDownloader | 통합 테스트 | MockWebServer |
| LiteRTEngine | 단위 테스트 (가짜 구현) | MockK 인터페이스 |
| GgufEngine | 단위 테스트 (LlamaJni mock) | MockK |
| InferenceEngineFactory | 단위 테스트 | JUnit5 |
| TunnelService | — | 미테스트 (Android 라이프사이클) |
| ViewModel / UI | — | 수동 / Espresso |
