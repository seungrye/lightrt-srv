package com.litert.tunnel.engine

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.File

class GgufEngine(private val jni: LlamaJniInterface = LlamaJni) : InferenceEngine {

    companion object {
        private const val TAG = "GgufEngine"
        private const val N_CTX = 4096
        private val N_THREADS = Runtime.getRuntime().availableProcessors().coerceAtMost(8)
    }

    private var handle: Long = 0L

    @Volatile private var maxConversationTurns: Int = EngineSettings.DEFAULT_MAX_TURNS
    @Volatile private var maxInputChars: Int         = EngineSettings.DEFAULT_MAX_INPUT_CHARS
    @Volatile private var contextReplayTurns: Int    = EngineSettings.DEFAULT_CONTEXT_REPLAY_TURNS

    private var conversationTurns: Int = 0

    private val _metrics = MutableStateFlow(EngineMetrics(maxConversationTurns = maxConversationTurns))
    override val metrics: StateFlow<EngineMetrics> = _metrics.asStateFlow()

    override var isReady: Boolean = false
        private set

    override var backendName: String = ""
        private set

    override var modelName: String = ""
        private set

    override suspend fun initialize(config: EngineConfig): Boolean = withContext(Dispatchers.IO) {
        modelName = File(config.modelPath).nameWithoutExtension

        for (backend in config.backendOrder) {
            val useVulkan = when (backend) {
                Backend.NPU    -> { Log.w(TAG, "NPU not supported in current build — skipping"); continue }
                Backend.GPU    -> true
                Backend.VULKAN -> true
                Backend.CPU    -> false
            }
            Log.i(TAG, "Trying backend: ${backend.displayName}")
            val h = jni.nativeInit(config.modelPath, N_THREADS, useVulkan, N_CTX)
            if (h != 0L) {
                handle = h
                backendName = backend.displayName
                isReady = true
                Log.i(TAG, "Engine ready — backend=$backendName model=$modelName template=${jni.nativeGetChatTemplate(h).take(80)}")
                return@withContext true
            }
            Log.w(TAG, "${backend.displayName} init failed — trying next backend")
        }

        Log.e(TAG, "All backends failed for: ${config.modelPath}")
        false
    }

    override fun generate(messages: List<Message>, toolsJson: String, enableThinking: Boolean): Flow<String> = channelFlow {
        val limit = maxConversationTurns

        if (conversationTurns >= limit) {
            Log.w(TAG, "Auto-resetting after $conversationTurns turns (limit=$limit)")
            _metrics.update { m ->
                m.copy(
                    conversationTurns = 0,
                    autoResetCount = m.autoResetCount + 1,
                    turnsHistory = (m.turnsHistory + conversationTurns + 0).takeLast(100),
                )
            }
            conversationTurns = 0
        }

        val lastUser = messages.lastOrNull { it.role == "user" }
            ?: error("No user message found")
        val inputLimit = maxInputChars
        val rawContent = lastUser.content
        val trimmedMessages = if (rawContent.length > inputLimit) {
            val trimmed = rawContent.take(inputLimit) + "\n…[trimmed to $inputLimit chars]"
            messages.dropLast(1) + lastUser.copy(content = trimmed)
        } else {
            messages
        }

        conversationTurns++
        _metrics.update { m ->
            m.copy(
                conversationTurns = conversationTurns,
                maxConversationTurns = limit,
                turnsHistory = (m.turnsHistory + conversationTurns).takeLast(100),
                inputSizeHistory = (m.inputSizeHistory + rawContent.length).takeLast(100),
            )
        }

        val roles               = trimmedMessages.map { it.role }.toTypedArray()
        val contents            = trimmedMessages.map { it.content }.toTypedArray()
        val toolCallsJsonPerMsg = trimmedMessages.map { msg ->
            if (msg.toolCalls.isNullOrEmpty()) ""
            else msg.toolCalls.joinToString(",", "[", "]") { tc ->
                """{"id":"${tc.id}","name":"${tc.name}","arguments":${tc.arguments}}"""
            }
        }.toTypedArray()
        val toolCallIds = trimmedMessages.map { it.toolCallId ?: "" }.toTypedArray()

        jni.nativeGenerate(handle, roles, contents, toolCallsJsonPerMsg, toolCallIds, toolsJson, enableThinking) { token ->
            trySend(token)
        }
    }.flowOn(Dispatchers.IO)

    override fun clearHistory() {
        if (handle == 0L) return
        jni.nativeClearContext(handle)
        conversationTurns = 0
        _metrics.update { it.copy(
            conversationTurns = 0,
            maxConversationTurns = maxConversationTurns,
            turnsHistory = emptyList(),
            inputSizeHistory = emptyList(),
        )}
        Log.i(TAG, "Context cleared")
    }

    override fun applySettings(settings: EngineSettings) {
        maxConversationTurns = settings.maxConversationTurns
        maxInputChars        = settings.maxInputChars
        contextReplayTurns   = settings.contextReplayTurns
        _metrics.update { it.copy(maxConversationTurns = settings.maxConversationTurns) }
    }

    override fun shutdown() {
        if (handle == 0L) return
        jni.nativeFree(handle)
        handle = 0L
        isReady = false
        Log.i(TAG, "Engine shut down")
    }
}
