package com.litert.tunnel.engine

import java.io.File

object InferenceEngineFactory {
    fun create(modelPath: String, context: android.content.Context): InferenceEngine =
        when (File(modelPath).extension.lowercase()) {
            "gguf"     -> GgufEngine()
            "litertlm" -> LiteRTEngine(context)
            else -> throw IllegalArgumentException(
                "Unsupported model format: ${File(modelPath).extension}"
            )
        }
}
