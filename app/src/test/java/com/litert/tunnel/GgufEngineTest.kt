package com.litert.tunnel

import com.litert.tunnel.engine.EngineConfig
import com.litert.tunnel.engine.EngineSettings
import com.litert.tunnel.engine.GgufEngine
import com.litert.tunnel.engine.LlamaJni
import com.litert.tunnel.engine.LlamaJniInterface
import com.litert.tunnel.engine.Message
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class GgufEngineTest {

    private lateinit var mockJni: LlamaJniInterface
    private lateinit var engine: GgufEngine

    @BeforeEach
    fun setUp() {
        mockJni = mockk<LlamaJniInterface>()
        engine = GgufEngine(jni = mockJni)
    }

    // ── initialize ────────────────────────────────────────────────────────────

    @Test
    fun `initialize returns true and sets isReady when native init succeeds`() = runTest {
        every { mockJni.nativeInit(any(), any(), any(), any()) } returns 42L
        every { mockJni.nativeGetChatTemplate(42L) } returns "..."

        val ok = engine.initialize(EngineConfig(modelPath = "/data/model.gguf"))

        assertTrue(ok)
        assertTrue(engine.isReady)
        assertEquals("GPU", engine.backendName)  // Backend.GPU.displayName
        assertEquals("model", engine.modelName)
    }

    @Test
    fun `initialize falls back to CPU when GPU init returns 0`() = runTest {
        every { mockJni.nativeInit("/data/model.gguf", any(), true, any()) } returns 0L
        every { mockJni.nativeInit("/data/model.gguf", any(), false, any()) } returns 7L
        every { mockJni.nativeGetChatTemplate(7L) } returns ""

        val ok = engine.initialize(EngineConfig(modelPath = "/data/model.gguf"))

        assertTrue(ok)
        assertEquals("CPU", engine.backendName)
    }

    @Test
    fun `initialize returns false when both GPU and CPU fail`() = runTest {
        every { mockJni.nativeInit(any(), any(), any(), any()) } returns 0L

        val ok = engine.initialize(EngineConfig(modelPath = "/data/model.gguf"))

        assertFalse(ok)
        assertFalse(engine.isReady)
    }

    // ── generate ──────────────────────────────────────────────────────────────

    @Test
    fun `generate passes correct roles and contents and emits tokens`() = runTest {
        every { mockJni.nativeInit(any(), any(), any(), any()) } returns 1L
        every { mockJni.nativeGetChatTemplate(1L) } returns ""

        val rolesSlot    = slot<Array<String>>()
        val contentsSlot = slot<Array<String>>()
        val callbackSlot = slot<LlamaJni.TokenCallback>()
        every {
            mockJni.nativeGenerate(eq(1L), capture(rolesSlot), capture(contentsSlot),
                any(), any(), any(), any(), capture(callbackSlot))
        } answers {
            callbackSlot.captured.onToken("Hello")
            callbackSlot.captured.onToken(" world")
        }

        engine.initialize(EngineConfig(modelPath = "/data/model.gguf"))
        val tokens = engine.generate(listOf(Message("user", "Hi"))).toList()

        assertEquals(listOf("Hello", " world"), tokens)
        assertTrue(rolesSlot.captured.contains("user"))
        assertTrue(contentsSlot.captured.contains("Hi"))
    }

    @Test
    fun `generate includes system message in correct position`() = runTest {
        every { mockJni.nativeInit(any(), any(), any(), any()) } returns 1L
        every { mockJni.nativeGetChatTemplate(1L) } returns ""

        val rolesSlot    = slot<Array<String>>()
        val contentsSlot = slot<Array<String>>()
        every {
            mockJni.nativeGenerate(any(), capture(rolesSlot), capture(contentsSlot),
                any(), any(), any(), any(), any())
        } answers {}

        engine.initialize(EngineConfig(modelPath = "/data/model.gguf"))
        engine.generate(listOf(
            Message("system", "You are helpful."),
            Message("user", "Hi"),
        )).toList()

        assertTrue(rolesSlot.captured.contains("system"))
        assertTrue(contentsSlot.captured.contains("You are helpful."))
    }

    @Test
    fun `generate trims user message exceeding maxInputChars`() = runTest {
        every { mockJni.nativeInit(any(), any(), any(), any()) } returns 1L
        every { mockJni.nativeGetChatTemplate(1L) } returns ""

        val contentsSlot = slot<Array<String>>()
        every {
            mockJni.nativeGenerate(any(), any(), capture(contentsSlot),
                any(), any(), any(), any(), any())
        } answers {}

        engine.initialize(EngineConfig(modelPath = "/data/model.gguf"))
        engine.applySettings(EngineSettings(maxInputChars = 10))
        engine.generate(listOf(Message("user", "A".repeat(100)))).toList()

        assertTrue(contentsSlot.captured.any { it.contains("trimmed") })
    }

    @Test
    fun `generate passes tools json to nativeGenerate`() = runTest {
        every { mockJni.nativeInit(any(), any(), any(), any()) } returns 1L
        every { mockJni.nativeGetChatTemplate(1L) } returns ""

        val toolsSlot = slot<String>()
        every {
            mockJni.nativeGenerate(any(), any(), any(), any(), any(),
                capture(toolsSlot), any(), any())
        } answers {}

        engine.initialize(EngineConfig(modelPath = "/data/model.gguf"))
        val toolsJson = """[{"type":"function","function":{"name":"get_weather"}}]"""
        engine.generate(listOf(Message("user", "What's the weather?")), toolsJson = toolsJson).toList()

        assertEquals(toolsJson, toolsSlot.captured)
    }

    @Test
    fun `generate passes tool call info for assistant messages`() = runTest {
        every { mockJni.nativeInit(any(), any(), any(), any()) } returns 1L
        every { mockJni.nativeGetChatTemplate(1L) } returns ""

        val tcJsonSlot = slot<Array<String>>()
        every {
            mockJni.nativeGenerate(any(), any(), any(), capture(tcJsonSlot),
                any(), any(), any(), any())
        } answers {}

        engine.initialize(EngineConfig(modelPath = "/data/model.gguf"))
        engine.generate(listOf(
            Message("user", "What's the weather?"),
            Message("assistant", "",
                toolCalls = listOf(com.litert.tunnel.engine.ToolCallInfo(
                    id = "call_1", name = "get_weather", arguments = """{"location":"Seoul"}"""
                ))
            ),
            Message("tool", """{"temperature":20}""", toolCallId = "call_1"),
        )).toList()

        // Assistant message at index 1 should have non-empty tool calls JSON
        assertTrue(tcJsonSlot.captured[1].contains("get_weather"))
        assertEquals("", tcJsonSlot.captured[0])  // user has no tool calls
    }

    // ── clearHistory ──────────────────────────────────────────────────────────

    @Test
    fun `clearHistory calls nativeClearContext`() = runTest {
        every { mockJni.nativeInit(any(), any(), any(), any()) } returns 1L
        every { mockJni.nativeGetChatTemplate(1L) } returns ""
        justRun { mockJni.nativeClearContext(1L) }

        engine.initialize(EngineConfig(modelPath = "/data/model.gguf"))
        engine.clearHistory()

        verify { mockJni.nativeClearContext(1L) }
    }

    // ── shutdown ──────────────────────────────────────────────────────────────

    @Test
    fun `shutdown calls nativeFree and sets isReady false`() = runTest {
        every { mockJni.nativeInit(any(), any(), any(), any()) } returns 1L
        every { mockJni.nativeGetChatTemplate(1L) } returns ""
        justRun { mockJni.nativeFree(1L) }

        engine.initialize(EngineConfig(modelPath = "/data/model.gguf"))
        engine.shutdown()

        verify { mockJni.nativeFree(1L) }
        assertFalse(engine.isReady)
    }
}
