package com.androidy.voicereader.llm

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * LiteRT-LM backend — Google's newest on-device LLM runtime (April 2026).
 * Supports Gemma 4 E2B/E4B with NPU/GPU acceleration.
 *
 * Uses .litertlm or .task model files from HuggingFace:
 *   litert-community/gemma-4-E2B-it-litert-lm
 *
 * LiteRT-LM provides:
 * - NPU acceleration on supported chipsets (Pixel, Samsung, Qualcomm)
 * - GPU fallback via OpenCL/Vulkan
 * - 4x faster inference vs MediaPipe on Gemma 4
 * - Streaming token generation
 */
class LiteRtLmBackend(private val context: Context) : LlmBackend {

    companion object {
        private const val TAG = "LiteRtLmBackend"
        private const val MAX_TOKENS = 1024
        private const val TEMPERATURE = 0.7f
        private const val TOP_K = 40
    }

    override val name = "LiteRT-LM"

    // LiteRT-LM engine instance — loaded via reflection to gracefully handle
    // cases where the library isn't available on the device
    private var engine: Any? = null
    private var generateMethod: java.lang.reflect.Method? = null
    private var closeMethod: java.lang.reflect.Method? = null

    override suspend fun load(modelPath: String) = withContext(Dispatchers.IO) {
        try {
            // Load LiteRT-LM via its API
            // com.google.ai.edge.litertlm.LlmEngine
            val engineClass = Class.forName("com.google.ai.edge.litertlm.LlmEngine")
            val builderClass = Class.forName("com.google.ai.edge.litertlm.LlmEngine\$Options\$Builder")

            val builder = builderClass.getDeclaredConstructor().newInstance()

            // Set model path
            builderClass.getMethod("setModelPath", String::class.java)
                .invoke(builder, modelPath)

            // Set generation parameters
            builderClass.getMethod("setMaxTokens", Int::class.javaPrimitiveType)
                .invoke(builder, MAX_TOKENS)
            builderClass.getMethod("setTemperature", Float::class.javaPrimitiveType)
                .invoke(builder, TEMPERATURE)
            builderClass.getMethod("setTopK", Int::class.javaPrimitiveType)
                .invoke(builder, TOP_K)

            val options = builderClass.getMethod("build").invoke(builder)
            val optionsClass = Class.forName("com.google.ai.edge.litertlm.LlmEngine\$Options")

            engine = engineClass.getMethod("create", Context::class.java, optionsClass)
                .invoke(null, context, options)

            generateMethod = engineClass.getMethod("generateResponse", String::class.java)
            closeMethod = engineClass.getMethod("close")

            Log.d(TAG, "LiteRT-LM engine loaded successfully with model: $modelPath")
        } catch (e: ClassNotFoundException) {
            Log.w(TAG, "LiteRT-LM library not available on this device")
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize LiteRT-LM", e)
            throw e
        }
    }

    override suspend fun generateResponse(prompt: String): String = withContext(Dispatchers.IO) {
        val eng = engine ?: throw IllegalStateException("LiteRT-LM engine not loaded")
        val result = generateMethod?.invoke(eng, prompt)
            ?: throw IllegalStateException("Generate method not available")
        result as String
    }

    override fun close() {
        try {
            closeMethod?.invoke(engine)
        } catch (e: Exception) {
            Log.w(TAG, "Error closing LiteRT-LM engine", e)
        }
        engine = null
        generateMethod = null
        closeMethod = null
    }

    fun isAvailable(): Boolean {
        return try {
            Class.forName("com.google.ai.edge.litertlm.LlmEngine")
            true
        } catch (e: ClassNotFoundException) {
            false
        }
    }
}
