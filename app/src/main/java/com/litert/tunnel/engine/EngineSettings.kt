package com.litert.tunnel.engine

enum class Backend {
    NPU,    // Qualcomm QNN / NNAPI — requires dedicated build support
    GPU,    // Hardware-accelerated (maps to Vulkan in current llama.cpp build)
    VULKAN, // Vulkan compute shaders explicitly
    CPU;    // CPU with NEON/SVE SIMD

    val displayName: String get() = when (this) {
        NPU    -> "NPU"
        GPU    -> "GPU"
        VULKAN -> "Vulkan"
        CPU    -> "CPU"
    }
}

data class EngineSettings(
    val maxConversationTurns: Int = DEFAULT_MAX_TURNS,
    val maxInputChars: Int = DEFAULT_MAX_INPUT_CHARS,
    val contextReplayTurns: Int = DEFAULT_CONTEXT_REPLAY_TURNS,
    val corsOrigins: List<String> = DEFAULT_CORS_ORIGINS,
    /**
     * Backend priority order. Engine tries each in sequence, falling back to the
     * next on failure. Takes effect on the next server start.
     */
    val backendOrder: List<Backend> = DEFAULT_BACKEND_ORDER,
) {
    companion object {
        const val DEFAULT_MAX_TURNS            = 8
        const val DEFAULT_MAX_INPUT_CHARS      = 1024
        const val DEFAULT_CONTEXT_REPLAY_TURNS = 3

        const val MIN_TURNS = 2
        const val MAX_TURNS = 24
        const val MIN_INPUT_CHARS = 256
        const val MAX_INPUT_CHARS = 4096
        const val MIN_REPLAY_TURNS = 0
        const val MAX_REPLAY_TURNS = 6

        val DEFAULT_CORS_ORIGINS   = listOf("localhost", "127.0.0.1", "192.168.*.*")
        val DEFAULT_BACKEND_ORDER  = listOf(Backend.GPU, Backend.VULKAN, Backend.CPU)
        const val CORS_ALLOW_ALL   = "*"
    }
}
