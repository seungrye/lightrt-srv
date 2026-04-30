# LiteRT Tunnel

Android app that loads a local LLM via [Google LiteRT-LM SDK](https://ai.google.dev/edge/litert) and exposes it as an **OpenAI-compatible HTTP API** on `localhost:8080` — so any OpenAI client can talk to an on-device model without internet access.

## Features

- **OpenAI-compatible API** — `/v1/chat/completions` (streaming + non-streaming), `/v1/models`, `/health`, `/reset`
- **GPU-first inference** — automatically falls back to CPU if GPU is unavailable
- **Built-in model downloader** — download Gemma 4 E2B / E4B directly from HuggingFace with resume support
- **Custom model support** — load any `.litertlm` file from device storage
- **LAN access** — server is reachable from other devices on the same Wi-Fi network
- **Real-time monitoring** — KV cache usage and observation size charts (100-point line charts)
- **Sliding-window context replay** — preserves recent conversation context across KV cache resets
- **Runtime-configurable settings** — max turns, max input chars, context replay turns (persisted)
- **Built-in chat UI** — test the model directly from the app
- **Korean / English UI** — switchable in settings

## Requirements

- Android 8.0+ (API 26)
- ~3–4 GB free storage for model files
- Device with NPU/GPU recommended for acceptable inference speed

## Built-in Models

| Model | Description | Size |
|-------|-------------|------|
| Gemma 4 E2B | 2B MoE · multimodal · 128K context | 2.58 GB |
| Gemma 4 E4B | 4B MoE · multimodal · 128K context | 3.65 GB |

## API Usage

### Health check
```bash
curl http://localhost:8080/health
```

### Chat completions (non-streaming)
```bash
curl http://localhost:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{
    "model": "local",
    "messages": [{"role": "user", "content": "Hello!"}]
  }'
```

### Chat completions (streaming)
```bash
curl http://localhost:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{
    "model": "local",
    "messages": [{"role": "user", "content": "Hello!"}],
    "stream": true
  }'
```

### Reset conversation history
```bash
curl -X POST http://localhost:8080/reset
```

## Python Example — ReAct Agent

The `examples/` directory contains a ReAct-style agent that uses the local server as its LLM backend.

```bash
# Run with default question
python examples/agent.py

# Run with custom question
python examples/agent.py "현재 디바이스의 저장공간 상황을 분석해줘"
```

The agent can execute shell commands on the device (requires Termux or similar) and reasons step-by-step to answer questions.

## Building

```bash
./gradlew assembleDebug
```

Install on connected device:
```bash
./gradlew installDebug
```

## Architecture

```
MainActivity
├── MainViewModel          ← StateFlow hub (UI state)
├── TunnelRepository       ← Singleton state (service ↔ UI bridge)
├── TunnelService          ← Android foreground service
│   ├── LiteRTEngine       ← LiteRT-LM SDK wrapper (GPU/CPU)
│   ├── TunnelServer       ← Ktor CIO HTTP server
│   └── SettingsRepository ← SharedPreferences (runtime settings)
└── ModelDownloader        ← OkHttp + resume support
```

**Key dependencies:**
- [LiteRT-LM SDK](https://ai.google.dev/edge/litert) `0.10.0` — on-device LLM inference
- [Ktor](https://ktor.io/) `2.3.12` — embedded HTTP server (CIO engine)
- [Jetpack Compose](https://developer.android.com/jetpack/compose) — UI
- [Kotlin Coroutines](https://kotlinlang.org/docs/coroutines-overview.html) — async/streaming

## License

Apache 2.0
