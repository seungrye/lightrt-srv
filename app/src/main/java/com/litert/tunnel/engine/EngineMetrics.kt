package com.litert.tunnel.engine

/**
 * Real-time snapshot of inference context state.
 * Updated on every [InferenceEngine.generate] call.
 *
 * @param conversationTurns   Current KV-cache depth (generate() calls since last reset)
 * @param maxConversationTurns Threshold at which the engine auto-resets
 * @param inputSizeHistory    Character count of each recent user message (last 20)
 * @param autoResetCount      How many times the engine auto-reset to prevent overflow
 */
data class EngineMetrics(
    val conversationTurns: Int = 0,
    val maxConversationTurns: Int = 8,
    /** Conversation turn depth recorded after each generate() call — up to 100 points. */
    val turnsHistory: List<Int> = emptyList(),
    /** Raw input character count recorded after each generate() call — up to 100 points. */
    val inputSizeHistory: List<Int> = emptyList(),
    val autoResetCount: Int = 0,
) {
    /** 0.0–1.0 fill ratio for the KV-cache pressure gauge. */
    val cachePressure: Float
        get() = if (maxConversationTurns > 0)
            (conversationTurns.toFloat() / maxConversationTurns).coerceIn(0f, 1f)
        else 0f
}
