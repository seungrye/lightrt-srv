package com.litert.tunnel

import com.litert.tunnel.engine.InferenceEngine
import com.litert.tunnel.engine.Message
import com.litert.tunnel.repository.RequestLog
import com.litert.tunnel.server.TunnelServer
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TunnelServerTest {

    private val json = Json { ignoreUnknownKeys = true }

    // Helper: builds a TunnelServer with a fake engine and installs it into testApplication
    private fun fakeEngine(
        ready: Boolean = true,
        backend: String = "GPU",
        modelName: String = "test-model",
        tokens: List<String> = listOf("Hello", " world")
    ): InferenceEngine {
        val engine = mockk<InferenceEngine>()
        every { engine.isReady } returns ready
        every { engine.backendName } returns backend
        every { engine.modelName } returns modelName
        val messagesSlot = slot<List<Message>>()
        every { engine.generate(capture(messagesSlot)) } returns flowOf(*tokens.toTypedArray())
        every { engine.clearHistory() } returns Unit
        return engine
    }

    @Test
    fun `GET health returns ok when engine is ready`() = testApplication {
        val engine = fakeEngine(ready = true)
        val logs = mutableListOf<RequestLog>()
        application { TunnelServer(engine) { logs.add(it) }.install(this) }

        val response = client.get("/health")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("ok", body["status"]?.jsonPrimitive?.content)
        assertEquals(true, body["ready"]?.jsonPrimitive?.content?.toBoolean())
        assertEquals("GPU", body["backend"]?.jsonPrimitive?.content)
    }

    @Test
    fun `GET health returns ready=false when engine not ready`() = testApplication {
        val engine = fakeEngine(ready = false)
        application { TunnelServer(engine) {}.install(this) }

        val response = client.get("/health")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(false, body["ready"]?.jsonPrimitive?.content?.toBoolean())
    }

    @Test
    fun `GET v1-models returns model list`() = testApplication {
        val engine = fakeEngine(modelName = "gemma-4-e2b")
        application { TunnelServer(engine) {}.install(this) }

        val response = client.get("/v1/models")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("list", body["object"]?.jsonPrimitive?.content)
        val data = body["data"]?.jsonArray
        assertEquals(1, data?.size)
        assertEquals("gemma-4-e2b", data?.get(0)?.jsonObject?.get("id")?.jsonPrimitive?.content)
    }

    @Test
    fun `POST chat-completions returns 503 when engine not ready`() = testApplication {
        val engine = fakeEngine(ready = false)
        application { TunnelServer(engine) {}.install(this) }

        val response = client.post("/v1/chat/completions") {
            contentType(ContentType.Application.Json)
            setBody("""{"model":"x","messages":[{"role":"user","content":"hi"}]}""")
        }
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(503, body["code"]?.jsonPrimitive?.content?.toInt())
    }

    @Test
    fun `POST chat-completions non-streaming returns full response`() = testApplication {
        val engine = fakeEngine(tokens = listOf("Hi", " there", "!"))
        application { TunnelServer(engine) {}.install(this) }

        val response = client.post("/v1/chat/completions") {
            contentType(ContentType.Application.Json)
            setBody(
                """
                {
                  "model": "test-model",
                  "messages": [{"role": "user", "content": "Hello"}],
                  "stream": false
                }
                """.trimIndent()
            )
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("chat.completion", body["object"]?.jsonPrimitive?.content)
        val choices = body["choices"]?.jsonArray
        assertEquals(1, choices?.size)
        val message = choices?.get(0)?.jsonObject?.get("message")?.jsonObject
        assertEquals("assistant", message?.get("role")?.jsonPrimitive?.content)
        assertEquals("Hi there!", message?.get("content")?.jsonPrimitive?.content)
        assertEquals("stop", choices?.get(0)?.jsonObject?.get("finish_reason")?.jsonPrimitive?.content)
    }

    @Test
    fun `POST chat-completions streaming returns SSE with DONE terminator`() = testApplication {
        val engine = fakeEngine(tokens = listOf("A", "B"))
        application { TunnelServer(engine) {}.install(this) }

        val response = client.post("/v1/chat/completions") {
            contentType(ContentType.Application.Json)
            setBody("""{"model":"x","messages":[{"role":"user","content":"hi"}],"stream":true}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val text = response.bodyAsText()
        // Should contain at least 3 data lines: role chunk, token chunks, stop chunk
        assertTrue(text.contains("data: "))
        assertTrue(text.contains("[DONE]"))
        // Token content
        assertTrue(text.contains("\"A\"") || text.contains("\\\"A\\\""))
        assertTrue(text.contains("\"B\"") || text.contains("\\\"B\\\""))
    }

    @Test
    fun `POST chat-completions passes system message to engine`() = testApplication {
        val engine = mockk<InferenceEngine>()
        every { engine.isReady } returns true
        every { engine.backendName } returns "CPU"
        every { engine.modelName } returns "m"
        val capturedMessages = slot<List<Message>>()
        every { engine.generate(capture(capturedMessages)) } returns flowOf("ok")
        application { TunnelServer(engine) {}.install(this) }

        client.post("/v1/chat/completions") {
            contentType(ContentType.Application.Json)
            setBody(
                """
                {
                  "model": "m",
                  "messages": [
                    {"role": "system", "content": "Be concise."},
                    {"role": "user",   "content": "Hello"}
                  ],
                  "stream": false
                }
                """.trimIndent()
            )
        }

        val messages = capturedMessages.captured
        assertTrue(messages.any { it.role == "system" && it.content == "Be concise." })
        assertTrue(messages.any { it.role == "user" && it.content == "Hello" })
    }

    @Test
    fun `POST reset clears engine history`() = testApplication {
        val engine = mockk<InferenceEngine>()
        every { engine.isReady } returns true
        every { engine.backendName } returns "CPU"
        every { engine.modelName } returns "m"
        every { engine.clearHistory() } returns Unit
        application { TunnelServer(engine) {}.install(this) }

        val response = client.post("/reset")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("cleared", body["status"]?.jsonPrimitive?.content)
        io.mockk.verify(exactly = 1) { engine.clearHistory() }
    }

    @Test
    fun `onRequest callback is invoked after each request`() = testApplication {
        val engine = fakeEngine()
        val logs = mutableListOf<RequestLog>()
        application { TunnelServer(engine) { logs.add(it) }.install(this) }

        client.get("/health")
        client.get("/v1/models")

        assertEquals(2, logs.size)
        assertEquals("/health", logs[0].endpoint)
        assertEquals(200, logs[0].statusCode)
        assertEquals("/v1/models", logs[1].endpoint)
    }
}
