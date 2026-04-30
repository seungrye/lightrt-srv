package com.litert.tunnel.engine

/**
 * User-configurable runtime parameters for the inference engine.
 * Changes take effect on the next [InferenceEngine.generate] call without restarting.
 *
 * @param maxConversationTurns  KV-cache auto-reset threshold (# of generate() calls)
 * @param maxInputChars         Server-side trim limit for each incoming user message
 */
data class EngineSettings(
    val maxConversationTurns: Int = DEFAULT_MAX_TURNS,
    val maxInputChars: Int = DEFAULT_MAX_INPUT_CHARS,
    /**
     * When the conversation is auto-reset, replay this many recent user messages
     * into the fresh Conversation to warm up the KV cache with recent context.
     * 0 = hard reset (current behaviour, instant). Higher = better continuity, slower reset.
     */
    val contextReplayTurns: Int = DEFAULT_CONTEXT_REPLAY_TURNS,
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
    }
}
