package com.litert.tunnel.engine

import android.util.Log

interface LlamaJniInterface {
    fun nativeInit(modelPath: String, nThreads: Int, useVulkan: Boolean, nCtx: Int): Long
    fun nativeGetChatTemplate(handle: Long): String
    fun nativeGenerate(
        handle: Long,
        roles: Array<String>,
        contents: Array<String>,
        toolCallsJsonPerMsg: Array<String>,
        toolCallIds: Array<String>,
        toolsJson: String,
        enableThinking: Boolean,
        callback: LlamaJni.TokenCallback,
    )
    fun nativeClearContext(handle: Long)
    fun nativeFree(handle: Long)
}

object LlamaJni : LlamaJniInterface {

    private const val TAG = "LlamaJni"

    init {
        // Load in dependency order (leaves first).
        // libomp.so ← libggml-cpu.so ← libggml.so ← libllama.so ← libllama_jni.so
        // libvulkan.so (system) ← libggml-vulkan.so ← libggml.so
        val deps = listOf("omp", "ggml-base", "ggml-vulkan", "ggml-cpu", "ggml", "llama", "llama_jni")
        for (lib in deps) {
            try {
                System.loadLibrary(lib)
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load lib$lib.so: ${e.message}")
            }
        }
    }

    fun interface TokenCallback {
        fun onToken(token: String)
    }

    override external fun nativeInit(modelPath: String, nThreads: Int, useVulkan: Boolean, nCtx: Int): Long
    override external fun nativeGetChatTemplate(handle: Long): String
    override external fun nativeGenerate(
        handle: Long,
        roles: Array<String>,
        contents: Array<String>,
        toolCallsJsonPerMsg: Array<String>,
        toolCallIds: Array<String>,
        toolsJson: String,
        enableThinking: Boolean,
        callback: TokenCallback,
    )
    override external fun nativeClearContext(handle: Long)
    override external fun nativeFree(handle: Long)
}
