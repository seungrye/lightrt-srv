package com.litert.tunnel.engine

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

data class Message(val role: String, val content: String)

data class EngineConfig(
    val modelPath: String,
    val useGpu: Boolean = true,
    val temperature: Double = 0.7,
    val topK: Int = 40,
    val topP: Double = 0.9,
    val maxTokens: Int = 1024,
)

/**
 * Abstraction over the LiteRT-LM SDK.
 * Keeping inference behind an interface lets tests inject a fake without the SDK.
 */
interface InferenceEngine {
    val isReady: Boolean
    val backendName: String
    val modelName: String
    val metrics: StateFlow<EngineMetrics>

    suspend fun initialize(config: EngineConfig): Boolean

    /**
     * Generates tokens from a full message history.
     * The implementation is responsible for extracting the system message (if any)
     * and replaying the conversation context before sending the last user message.
     */
    fun generate(messages: List<Message>): Flow<String>

    /** Resets conversation context without releasing native resources. */
    fun clearHistory()

    /**
     * Applies new runtime settings immediately — no restart required.
     * Thread-safe: takes effect on the next [generate] call.
     */
    fun applySettings(settings: EngineSettings)

    /** Releases all native resources. Must be called in [onDestroy]. */
    fun shutdown()
}
