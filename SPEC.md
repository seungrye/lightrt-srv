# LiteRT Tunnel — Specification

LiteRT Tunnel is an Android app that loads a local LLM via Google's LiteRT-LM SDK and
exposes it as an OpenAI-compatible HTTP API on `localhost:8080`.

---

## 1. Components

### 1.1 LiteRTEngine

Wraps the LiteRT-LM SDK. Abstracted behind an interface so tests can fake it.

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

**Behaviour:**
- `initialize` tries GPU first; on failure falls back to CPU automatically.
- `generate` accepts a full message list (`system`, `user`, `assistant` roles).
  - System message is applied as the conversation's `systemInstruction`.
  - If no system message is provided a default is used.
  - User/assistant turns are replayed in order before sending the new user message.
- `clearHistory` resets the conversation object but keeps the engine alive.
- `shutdown` releases all native resources.

---

### 1.2 TunnelRepository

Singleton state hub. Both `TunnelService` (writer) and `MainViewModel` (reader) use it.
No BroadcastReceiver needed.

```
enum class TunnelStatus { IDLE, LOADING, RUNNING, ERROR, STOPPED }

data class TunnelState(
    val status: TunnelStatus = TunnelStatus.IDLE,
    val port: Int = 8080,
    val backendName: String = "",
    val modelName: String = "",
    val error: String? = null,
    val requestCount: Long = 0,
    val recentLogs: List<RequestLog> = emptyList(),  // last 100
)
```

**State transitions:**
```
IDLE ──start()──► LOADING ──success──► RUNNING
                          └──failure──► ERROR
RUNNING ──stop()──► STOPPED ──start()──► LOADING
ERROR   ──start()──► LOADING
```

**Methods:**
- `fun start(modelPath: String, useGpu: Boolean)` — triggers `TunnelService`
- `fun stop()` — stops service
- `fun appendLog(log: RequestLog)` — called by `TunnelServer` after each request

---

### 1.3 TunnelServer

Ktor CIO HTTP server. Fully injectable (takes `InferenceEngine` interface).

**Endpoints:**

| Method | Path | Description |
|--------|------|-------------|
| GET | `/health` | Server liveness + engine readiness |
| GET | `/v1/models` | OpenAI-compatible models list |
| POST | `/v1/chat/completions` | Chat completions (streaming + non-streaming) |
| POST | `/reset` | Clear conversation history |

**POST /v1/chat/completions request:**
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

**Behaviour:**
- Returns `503` with `{"error": "...", "code": 503}` when engine is not ready.
- `stream: true` → SSE (`text/event-stream`), each token as `data: {...}\n\n`, ends with `data: [DONE]\n\n`.
- `stream: false` → single JSON response.
- Unknown fields in request are ignored (`ignoreUnknownKeys = true`).
- Tries ports 8080→8082 sequentially; returns the port it bound to.

---

### 1.4 ModelDownloader

Downloads `.litertlm` files via HTTP with resume support.

**Behaviour:**
- Accepts any URL (not hardcoded to HuggingFace).
- Emits `DownloadProgress(progressFraction, downloadedBytes, totalBytes, speedBps, isDone, error)`.
- Sends `Range: bytes=N-` header if the destination file already exists (resume).
- Validates final file size against `minValidBytes`; deletes and throws on mismatch.
- Caller-supplied `destFile: File` — no path opinions inside the downloader.

---

### 1.5 Network Security

Android 9+ blocks cleartext HTTP by default. LiteRT Tunnel communicates with its own
server on `localhost` via HTTP (not HTTPS), so cleartext must be explicitly permitted
**only for localhost** via `res/xml/network_security_config.xml`.

External traffic (HuggingFace downloads) must remain HTTPS — the config must not
relax security for any domain other than `localhost` / `127.0.0.1`.

---

## 2. API Contract (OpenAI-compatible)

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

### POST /v1/chat/completions (non-streaming)
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

### POST /v1/chat/completions (streaming, SSE)
```
data: {"id":"chatcmpl-...","object":"chat.completion.chunk","created":...,"model":"...","choices":[{"index":0,"delta":{"role":"assistant","content":""},"finish_reason":null}]}

data: {"id":"chatcmpl-...","object":"chat.completion.chunk","created":...,"model":"...","choices":[{"index":0,"delta":{"content":"Hello"},"finish_reason":null}]}

data: {"id":"chatcmpl-...","object":"chat.completion.chunk","created":...,"model":"...","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

data: [DONE]
```

---

## 3. Test Strategy

| Layer | Test type | Tool |
|-------|-----------|------|
| TunnelRepository | Unit | JUnit5 + Turbine |
| TunnelServer | Integration | Ktor `testApplication` |
| ModelDownloader | Integration | MockWebServer |
| LiteRTEngine | Unit (via fake) | MockK interface |
| TunnelService | — | Not unit tested (Android lifecycle) |
| ViewModel / UI | — | Manual / Espresso |
