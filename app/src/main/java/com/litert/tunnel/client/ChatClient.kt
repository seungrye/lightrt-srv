package com.litert.tunnel.client

import com.litert.tunnel.engine.Message
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class ChatClient(
    private val port: Int,
    private val host: String = "localhost",
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)  // long — model can be slow
        .build(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * POSTs to /v1/chat/completions with stream=true and emits tokens as they arrive.
     * Throws on non-2xx HTTP status.
     */
    fun streamMessage(messages: List<Message>): Flow<String> = flow {
        val payload = buildRequestJson(messages, stream = true)
        val request = Request.Builder()
            .url("http://$host:$port/v1/chat/completions")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            throw Exception("HTTP ${response.code}: ${response.body?.string() ?: response.message}")
        }

        val source = response.body?.source()
            ?: throw Exception("Empty response body")

        source.use {
            while (!it.exhausted()) {
                val line = it.readUtf8Line() ?: break
                if (!line.startsWith("data: ")) continue
                val data = line.removePrefix("data: ").trim()
                if (data == "[DONE]") break

                val token = parseToken(data)
                if (token != null) emit(token)
            }
        }
    }.flowOn(Dispatchers.IO)

    private fun buildRequestJson(messages: List<Message>, stream: Boolean): String {
        val messagesJson = messages.joinToString(",") { msg ->
            """{"role":"${msg.role}","content":${Json.encodeToString(msg.content)}}"""
        }
        return """{"model":"local","messages":[$messagesJson],"stream":$stream}"""
    }

    /**
     * Extracts the token string from a single SSE data line.
     * Returns null for role-only chunks (content is null or empty) and stop chunks.
     */
    private fun parseToken(data: String): String? {
        return runCatching {
            val obj = json.parseToJsonElement(data).jsonObject
            val delta = obj["choices"]
                ?.jsonArray
                ?.getOrNull(0)
                ?.jsonObject
                ?.get("delta")
                ?.jsonObject
                ?: return null

            // contentOrNull returns null for JSON null literals
            // (JsonNull.content would return the string "null", which is wrong)
            val content = delta["content"]?.jsonPrimitive?.contentOrNull
            if (content.isNullOrEmpty()) null else content
        }.getOrNull()
    }
}
