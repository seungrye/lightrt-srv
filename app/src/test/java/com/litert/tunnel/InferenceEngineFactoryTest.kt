package com.litert.tunnel

import android.content.Context
import com.litert.tunnel.engine.GgufEngine
import com.litert.tunnel.engine.InferenceEngineFactory
import com.litert.tunnel.engine.LiteRTEngine
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class InferenceEngineFactoryTest {

    private val mockContext = mockk<Context>(relaxed = true)

    @Test
    fun `create returns GgufEngine for gguf extension`() {
        val engine = InferenceEngineFactory.create("/data/models/qwen2.5-1.5b.gguf", mockContext)
        assertInstanceOf(GgufEngine::class.java, engine)
    }

    @Test
    fun `create returns LiteRTEngine for litertlm extension`() {
        val engine = InferenceEngineFactory.create("/data/models/gemma-4-e2b.litertlm", mockContext)
        assertInstanceOf(LiteRTEngine::class.java, engine)
    }

    @Test
    fun `create is case-insensitive for extension`() {
        val engine = InferenceEngineFactory.create("/data/models/model.GGUF", mockContext)
        assertInstanceOf(GgufEngine::class.java, engine)
    }

    @Test
    fun `create throws for unsupported extension`() {
        assertThrows(IllegalArgumentException::class.java) {
            InferenceEngineFactory.create("/data/models/model.bin", mockContext)
        }
    }
}
