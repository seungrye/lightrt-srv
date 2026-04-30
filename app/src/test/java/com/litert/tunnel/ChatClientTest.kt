package com.litert.tunnel

import app.cash.turbine.test
import com.litert.tunnel.client.ChatClient
import com.litert.tunnel.engine.Message
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ChatClientTest {

    private lateinit var server: MockWebServer

    @BeforeEach
    fun setUp() { server = MockWebServer(); server.start() }

    @AfterEach
    fun tearDown() { server.shutdown() }

    private fun sseBody(vararg tokens: String): String = buildString {
        // Opening role chunk
        append("data: {\"id\":\"1\",\"object\":\"chat.completion.chunk\",\"created\":0,\"model\":\"m\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"\"},\"finish_reason\":null}]}\n\n")
        for (token in tokens) {
            val escaped = token.replace("\"", "\\\"")
            append("data: {\"id\":\"1\",\"object\":\"chat.completion.chunk\",\"created\":0,\"model\":\"m\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"$escaped\"},\"finish_reason\":null}]}\n\n")
        }
        // Stop chunk — content is JSON null (as serialized by kotlinx with encodeDefaults=true)
        append("data: {\"id\":\"1\",\"object\":\"chat.completion.chunk\",\"created\":0,\"model\":\"m\",\"choices\":[{\"index\":0,\"delta\":{\"role\":null,\"content\":null},\"finish_reason\":\"stop\"}]}\n\n")
        append("data: [DONE]\n\n")
    }

    @Test
    fun `streams tokens from SSE response`() = runTest {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(Buffer().writeUtf8(sseBody("안녕", "하세", "요")))
        )

        val client = ChatClient(server.port, "localhost")
        client.streamMessage(listOf(Message("user", "안녕"))).test {
            assertEquals("안녕", awaitItem())
            assertEquals("하세", awaitItem())
            assertEquals("요", awaitItem())
            awaitComplete()
        }

        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertTrue(req.path!!.endsWith("/v1/chat/completions"))
        assertTrue(req.body.readUtf8().contains("\"stream\":true"))
    }

    @Test
    fun `ignores role-only opening chunk and stop chunk`() = runTest {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(Buffer().writeUtf8(sseBody("Hello")))
        )

        val client = ChatClient(server.port, "localhost")
        val tokens = mutableListOf<String>()
        client.streamMessage(listOf(Message("user", "hi"))).test {
            tokens.add(awaitItem())
            awaitComplete()
        }
        assertEquals(listOf("Hello"), tokens)
    }

    @Test
    fun `emits error on non-2xx response`() = runTest {
        server.enqueue(MockResponse().setResponseCode(503).setBody("{\"error\":\"not ready\",\"code\":503}"))

        val client = ChatClient(server.port, "localhost")
        client.streamMessage(listOf(Message("user", "hi"))).test {
            val err = awaitError()
            assertTrue(err.message!!.contains("503"), "Expected 503 in error, got: ${err.message}")
        }
    }

    @Test
    fun `passes all messages including system role`() = runTest {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(Buffer().writeUtf8(sseBody("ok")))
        )

        val messages = listOf(
            Message("system", "Be concise."),
            Message("user", "Hello"),
        )
        val client = ChatClient(server.port, "localhost")
        client.streamMessage(messages).test {
            awaitItem(); awaitComplete()
        }

        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"system\""))
        assertTrue(body.contains("Be concise."))
        assertTrue(body.contains("\"user\""))
    }

    @Test
    fun `stop chunk with JSON null content does not emit the string null`() = runTest {
        // Reproduces the bug: kotlinx encodeDefaults=true serializes Delta() as
        // {"role":null,"content":null}. JsonNull.content returns the string "null",
        // which was previously emitted as a token.
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(Buffer().writeUtf8(sseBody("Hello")))
        )

        val client = ChatClient(server.port, "localhost")
        val tokens = mutableListOf<String>()
        client.streamMessage(listOf(Message("user", "hi"))).test {
            tokens.add(awaitItem())
            awaitComplete()
        }

        assertEquals(listOf("Hello"), tokens, "Only real tokens should be emitted — no 'null' string from stop chunk")
    }
}
