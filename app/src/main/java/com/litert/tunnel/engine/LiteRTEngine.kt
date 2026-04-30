package com.litert.tunnel.engine

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig as LiteRTEngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import com.litert.tunnel.engine.Backend as EngineBackend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.File

class LiteRTEngine(private val context: Context) : InferenceEngine {

    companion object {
        private const val TAG = "LiteRTEngine"
        private const val DEFAULT_SYSTEM_PROMPT =
            "You are a helpful AI assistant running locally on an Android device " +
            "via Google's LiteRT-LM SDK."
    }

    private var engine: Engine? = null
    private var conversation: com.google.ai.edge.litertlm.Conversation? = null
    private var samplerConfig: SamplerConfig = SamplerConfig(topK = 40, topP = 0.9, temperature = 0.7)
    private var currentSystemPrompt: String = DEFAULT_SYSTEM_PROMPT
    private var conversationTurns: Int = 0

    // Runtime-configurable settings — @Volatile so generate() (IO thread) always sees latest value
    @Volatile private var maxConversationTurns: Int = EngineSettings.DEFAULT_MAX_TURNS
    @Volatile private var maxInputChars: Int         = EngineSettings.DEFAULT_MAX_INPUT_CHARS
    @Volatile private var contextReplayTurns: Int    = EngineSettings.DEFAULT_CONTEXT_REPLAY_TURNS

    private val _metrics = MutableStateFlow(EngineMetrics(maxConversationTurns = maxConversationTurns))
    override val metrics: StateFlow<EngineMetrics> = _metrics.asStateFlow()

    override var isReady: Boolean = false
        private set

    override var backendName: String = ""
        private set

    override var modelName: String = ""
        private set

    override suspend fun initialize(config: EngineConfig): Boolean {
        return withContext(Dispatchers.IO) {
            modelName = File(config.modelPath).nameWithoutExtension
            samplerConfig = SamplerConfig(
                topK = config.topK,
                topP = config.topP,
                temperature = config.temperature,
            )
            for (backend in config.backendOrder) {
                Log.i(TAG, "Trying backend: ${backend.displayName}")
                val ok = tryInitBackend(config, backend)
                if (ok) return@withContext true
                Log.w(TAG, "${backend.displayName} failed — trying next backend")
            }
            Log.e(TAG, "All backends failed for: ${config.modelPath}")
            false
        }
    }

    private suspend fun tryInitBackend(config: EngineConfig, backend: EngineBackend): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val liteRTBackend: Backend = when (backend) {
                    EngineBackend.NPU    -> Backend.GPU() // LiteRT may support NPU via GPU delegation
                    EngineBackend.GPU    -> Backend.GPU()
                    EngineBackend.VULKAN -> Backend.GPU()
                    EngineBackend.CPU    -> Backend.CPU()
                }
                val liteRTConfig = LiteRTEngineConfig(
                    modelPath = config.modelPath,
                    backend = liteRTBackend,
                    visionBackend = liteRTBackend,
                    cacheDir = context.cacheDir.absolutePath,
                )
                val newEngine = Engine(liteRTConfig)
                newEngine.initialize()

                engine = newEngine
                conversation = newConversation(newEngine, currentSystemPrompt)
                conversationTurns = 0
                _metrics.update { EngineMetrics(maxConversationTurns = maxConversationTurns) }
                backendName = backend.displayName
                isReady = true
                Log.i(TAG, "Engine ready — backend=$backendName model=$modelName")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Init failed (backend=${backend.displayName})", e)
                isReady = false
                false
            }
        }
    }

    /**
     * Generates tokens from a message list.
     *
     * - Extracts the `system` message; rebuilds conversation if it changed.
     * - Auto-resets after [maxConversationTurns] to prevent KV-cache overflow (SIGSEGV).
     * - On reset: replays the last [contextReplayTurns] prior user messages into the fresh
     *   Conversation (sliding-window context preservation). Replay tokens are discarded —
     *   only the KV cache warmup matters.
     * - Trims each user message to [maxInputChars] before inference.
     * - Updates [metrics] on every call.
     */
    override fun generate(messages: List<Message>, toolsJson: String, enableThinking: Boolean): Flow<String> = flow {
        val systemContent = messages.firstOrNull { it.role == "system" }?.content
        val turns = messages.filter { it.role != "system" }

        // Rebuild conversation when system prompt changes
        if (systemContent != null && systemContent != currentSystemPrompt) {
            currentSystemPrompt = systemContent
            engine?.let { eng ->
                conversation?.close()
                conversation = newConversation(eng, currentSystemPrompt)
                conversationTurns = 0
            }
        }

        val limit  = maxConversationTurns
        val replay = contextReplayTurns

        // Auto-reset before the turn that would overflow the context window
        if (conversationTurns >= limit) {
            Log.w(TAG, "Auto-resetting after $conversationTurns turns (limit=$limit, replay=$replay)")
            val eng = engine ?: error("Engine not initialized")
            conversation?.close()
            val newConv = newConversation(eng, currentSystemPrompt)
            conversation = newConv

            _metrics.update { m ->
                m.copy(
                    conversationTurns = 0,
                    autoResetCount = m.autoResetCount + 1,
                    turnsHistory = (m.turnsHistory + conversationTurns + 0).takeLast(100),
                )
            }
            conversationTurns = 0

            // Sliding-window replay: feed the last N prior user messages to warm up the KV
            // cache so the model retains recent context after the reset.
            // We collect and discard generated tokens — only the KV state matters.
            if (replay > 0) {
                val priorUserMessages = turns
                    .filter { it.role == "user" }
                    .dropLast(1)        // exclude the current (new) query
                    .takeLast(replay)
                Log.d(TAG, "Replaying ${priorUserMessages.size} turns for context warmup")
                for (msg in priorUserMessages) {
                    newConv.sendMessageAsync(msg.content.take(maxInputChars))
                        .collect { /* discard — KV warmup only */ }
                    conversationTurns++
                }
            }
        }

        val conv = conversation ?: error("Engine not initialized")

        val lastUserMessage = turns.lastOrNull { it.role == "user" }
            ?: error("No user message found")

        val inputLimit = maxInputChars
        val rawContent = lastUserMessage.content
        val prompt = if (rawContent.length > inputLimit)
            rawContent.take(inputLimit) + "\n…[trimmed to $inputLimit chars by server]"
        else rawContent

        conversationTurns++
        _metrics.update { m ->
            m.copy(
                conversationTurns = conversationTurns,
                maxConversationTurns = limit,
                turnsHistory = (m.turnsHistory + conversationTurns).takeLast(100),
                inputSizeHistory = (m.inputSizeHistory + rawContent.length).takeLast(100),
            )
        }

        conv.sendMessageAsync(prompt).collect { emit(it.toString()) }
    }.flowOn(Dispatchers.IO)

    override fun clearHistory() {
        val eng = engine ?: return
        conversation?.close()
        conversation = newConversation(eng, currentSystemPrompt)
        conversationTurns = 0
        _metrics.update { it.copy(
            conversationTurns = 0,
            maxConversationTurns = maxConversationTurns,
            turnsHistory = emptyList(),
            inputSizeHistory = emptyList(),
        )}
        Log.i(TAG, "Conversation history cleared")
    }

    override fun applySettings(settings: EngineSettings) {
        maxConversationTurns = settings.maxConversationTurns
        maxInputChars        = settings.maxInputChars
        contextReplayTurns   = settings.contextReplayTurns
        _metrics.update { it.copy(maxConversationTurns = settings.maxConversationTurns) }
        Log.i(TAG, "Settings applied: maxTurns=$maxConversationTurns " +
                "maxInputChars=$maxInputChars replayTurns=$contextReplayTurns")
    }

    override fun shutdown() {
        isReady = false
        conversation?.close()
        conversation = null
        engine?.close()
        engine = null
        Log.i(TAG, "Engine shut down")
    }

    private fun newConversation(eng: Engine, systemPrompt: String) =
        eng.createConversation(
            ConversationConfig(
                systemInstruction = Contents.of(Content.Text(systemPrompt)),
                samplerConfig = samplerConfig,
            )
        )
}
