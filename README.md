# LiteRT Tunnel

---

> ## 🤖 이 프로젝트는 100% AI가 만들었습니다
>
> **단 한 줄의 코드도 인간이 직접 작성하지 않았습니다.**
> 아이디어 기획부터 아키텍처 설계, 전체 코드 구현, 문서화까지
> 모든 것이 [Claude](https://claude.ai) (Anthropic)에 의해 작성되었습니다.
>
> _This project is fully AI-driven — designed, implemented, and documented by Claude._

---

[Google LiteRT-LM SDK](https://ai.google.dev/edge/litert)를 통해 온디바이스 LLM을 구동하고, **OpenAI 호환 HTTP API**(`localhost:8080`)로 노출하는 Android 앱입니다. 인터넷 없이도 모든 OpenAI 클라이언트가 기기 내 모델과 통신할 수 있습니다.

## 주요 기능

- **OpenAI 호환 API** — `/v1/chat/completions` (스트리밍 / 비스트리밍), `/v1/models`, `/health`, `/reset`
- **GPU 우선 추론** — GPU 사용 불가 시 CPU로 자동 폴백
- **내장 모델 다운로더** — HuggingFace에서 Gemma 4 E2B / E4B 직접 다운로드 (이어받기 지원)
- **커스텀 모델 지원** — 기기 내 `.litertlm` 파일 직접 불러오기
- **LAN 접근** — 같은 Wi-Fi 네트워크의 다른 기기에서도 접근 가능
- **실시간 모니터링** — KV 캐시 사용량 및 입력 크기 차트 (최대 100개 라인 차트)
- **슬라이딩 윈도우 컨텍스트 복원** — KV 캐시 리셋 후 최근 대화 맥락 자동 복원
- **런타임 설정** — 최대 턴 수, 최대 입력 문자 수, 컨텍스트 복원 턴 수 (앱 재시작 후에도 유지)
- **내장 채팅 UI** — 앱에서 바로 모델 테스트 가능
- **한국어 / 영어 UI** — 설정에서 전환 가능

## 요구사항

- Android 8.0 이상 (API 26)
- 모델 파일 저장을 위한 여유 공간 3~4 GB
- 빠른 추론 속도를 위해 NPU/GPU 탑재 기기 권장

## 내장 모델

| 모델 | 설명 | 크기 |
|------|------|------|
| Gemma 4 E2B | 2B MoE · 멀티모달 · 128K 컨텍스트 | 2.58 GB |
| Gemma 4 E4B | 4B MoE · 멀티모달 · 128K 컨텍스트 | 3.65 GB |

## API 사용법

### 상태 확인
```bash
curl http://localhost:8080/health
```

### 채팅 완성 (비스트리밍)
```bash
curl http://localhost:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{
    "model": "local",
    "messages": [{"role": "user", "content": "안녕!"}]
  }'
```

### 채팅 완성 (스트리밍)
```bash
curl http://localhost:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{
    "model": "local",
    "messages": [{"role": "user", "content": "안녕!"}],
    "stream": true
  }'
```

### 대화 기록 초기화
```bash
curl -X POST http://localhost:8080/reset
```

## Python 예제 — ReAct 에이전트

`examples/` 디렉터리에 로컬 서버를 LLM 백엔드로 사용하는 ReAct 스타일 에이전트가 포함되어 있습니다.

```bash
# 기본 질문으로 실행
python examples/agent.py

# 직접 질문 지정
python examples/agent.py "현재 디바이스의 저장공간 상황을 분석해줘"
```

에이전트는 기기에서 셸 명령어를 실행(Termux 등 필요)하고, 단계별로 추론하며 질문에 답변합니다.

## 빌드

```bash
./gradlew assembleDebug
```

연결된 기기에 설치:
```bash
./gradlew installDebug
```

## 아키텍처

```
MainActivity
├── MainViewModel          ← StateFlow 허브 (UI 상태)
├── TunnelRepository       ← 싱글톤 상태 (서비스 ↔ UI 브릿지)
├── TunnelService          ← Android 포그라운드 서비스
│   ├── LiteRTEngine       ← LiteRT-LM SDK 래퍼 (GPU/CPU)
│   ├── TunnelServer       ← Ktor CIO HTTP 서버
│   └── SettingsRepository ← SharedPreferences (런타임 설정)
└── ModelDownloader        ← OkHttp + 이어받기 지원
```

**주요 의존성:**
- [LiteRT-LM SDK](https://ai.google.dev/edge/litert) `0.10.0` — 온디바이스 LLM 추론
- [Ktor](https://ktor.io/) `2.3.12` — 임베디드 HTTP 서버 (CIO 엔진)
- [Jetpack Compose](https://developer.android.com/jetpack/compose) — UI
- [Kotlin Coroutines](https://kotlinlang.org/docs/coroutines-overview.html) — 비동기 / 스트리밍

## 라이선스

Apache 2.0
