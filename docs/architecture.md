# 아키텍처

Android Foreground Service가 LiteRT-LM 추론 엔진과 Ktor HTTP 서버를 동시에 구동한다. UI와 서비스 간 통신은 BroadcastReceiver 없이 `TunnelRepository` 싱글톤의 `StateFlow`로 이루어진다.

```
MainActivity / Compose UI
      │ observes
      ▼
  MainViewModel
      │ observes
      ▼
  TunnelRepository  ◄─── writes ─── TunnelService
      │                                   │
      │                          ┌────────┴────────┐
      │                     LiteRTEngine      TunnelServer
      │                    (InferenceEngine)   (Ktor CIO)
      └── engineMetrics ◄──── metrics StateFlow
```

## 컴포넌트

### InferenceEngine (`engine/InferenceEngine.kt`)
LiteRT-LM SDK를 격리하는 추상 경계. `generate()`는 전체 메시지 히스토리(`List<Message>`)를 받아 `Flow<String>`으로 토큰을 방출한다. 테스트에서는 MockK로 대체한다.

### LiteRTEngine (`engine/LiteRTEngine.kt`)
`InferenceEngine`의 실제 구현체. 주요 동작:
- 초기화 시 GPU 먼저 시도 → 실패 시 CPU 자동 폴백.
- `maxConversationTurns` 도달 시 KV 캐시 오버플로(SIGSEGV) 방지를 위해 대화 자동 리셋.
- 리셋 후 `contextReplayTurns`만큼 최근 메시지를 새 Conversation에 재주입해 컨텍스트 복원(슬라이딩 윈도우).
- 설정값(`maxConversationTurns`, `maxInputChars`, `contextReplayTurns`)은 `@Volatile`로 선언되어 재시작 없이 즉시 반영.

### TunnelServer (`server/TunnelServer.kt`)
`InferenceEngine`을 생성자 주입받는 Ktor CIO 서버. `install(app: Application)` 메서드로 라우팅을 등록하므로 실제 서버와 테스트(`testApplication`) 모두 동일 코드 경로를 사용한다. 포트 8080→8082 순으로 바인딩 시도.

CORS는 `EngineSettings.corsOrigins` 패턴 목록과 매 요청의 Origin 헤더를 비교한다. 패턴에서 `*`는 글로브 와일드카드(`192.168.*.*` → 정규식)로 변환된다.

### TunnelService (`service/TunnelService.kt`)
Foreground Service. `onStartCommand`에서 엔진 초기화 → 서버 시작을 순차 실행. `settingsRepository.settings` Flow를 collect해 런타임 설정 변경을 엔진과 서버에 실시간 반영한다.

### TunnelRepository (`repository/TunnelRepository.kt`)
Service(쓰기)와 ViewModel(읽기) 간 상태 허브. `TunnelApplication`에서 싱글톤으로 생성되어 `TunnelService.repository`에 정적 필드로 주입된다.

상태 전이: `IDLE → LOADING → RUNNING | ERROR → STOPPED`

### EngineSettings (`engine/EngineSettings.kt`)
런타임 적용 가능한 설정값. `SettingsRepository`가 DataStore에 영속화하고, `TunnelService`가 collect해 엔진·서버에 전달한다.
