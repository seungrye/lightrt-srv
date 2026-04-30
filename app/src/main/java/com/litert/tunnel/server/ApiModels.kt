package com.litert.tunnel.server

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ── Tool types ───────────────────────────────────────────────────────────────

@Serializable
data class ToolDefinition(
    val type: String = "function",
    val function: FunctionDefinition,
)

@Serializable
data class FunctionDefinition(
    val name: String,
    val description: String? = null,
    val parameters: kotlinx.serialization.json.JsonElement? = null,
)

@Serializable
data class ToolCall(
    val id: String = "",
    val type: String = "function",
    val function: FunctionCallInfo,
)

@Serializable
data class FunctionCallInfo(
    val name: String,
    val arguments: String = "",
)

// ── Requests ────────────────────────────────────────────────────────────────

@Serializable
data class ChatCompletionRequest(
    val model: String = "",
    val messages: List<MessageDto>,
    val stream: Boolean = false,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    val temperature: Double? = null,
    val tools: List<ToolDefinition>? = null,
    @SerialName("tool_choice") val toolChoice: String? = null,
)

@Serializable
data class MessageDto(
    val role: String,
    val content: String = "",
    @SerialName("tool_calls") val toolCalls: List<ToolCall>? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null,
    val name: String? = null,
)

// ── Responses ────────────────────────────────────────────────────────────────

@Serializable
data class HealthResponse(
    val status: String,
    val model: String,
    val backend: String,
    val ready: Boolean,
)

@Serializable
data class ErrorResponse(val error: String, val code: Int)

@Serializable
data class ModelsResponse(
    val `object`: String = "list",
    val data: List<ModelEntry>,
)

@Serializable
data class ModelEntry(
    val id: String,
    val `object`: String = "model",
    val created: Long = 0L,
    @SerialName("owned_by") val ownedBy: String = "local",
)

@Serializable
data class ChatCompletionResponse(
    val id: String,
    val `object`: String = "chat.completion",
    val created: Long,
    val model: String,
    val choices: List<Choice>,
)

@Serializable
data class Choice(
    val index: Int,
    val message: MessageDto,
    @SerialName("finish_reason") val finishReason: String,
)

// ── Streaming (SSE) ──────────────────────────────────────────────────────────

@Serializable
data class StreamChunk(
    val id: String,
    val `object`: String = "chat.completion.chunk",
    val created: Long,
    val model: String,
    val choices: List<StreamChoice>,
)

@Serializable
data class StreamChoice(
    val index: Int,
    val delta: Delta,
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
data class Delta(
    val role: String? = null,
    val content: String? = null,
)
