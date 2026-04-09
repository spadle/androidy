package com.androidy.voicereader.llm

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * LiteRT-LM backend — Google's on-device LLM runtime (successor to MediaPipe LLM Inference).
 *
 * Built on LiteRT (formerly TensorFlow Lite), with LLM-specific optimizations:
 * - NPU acceleration on supported chipsets (Pixel, Samsung, Qualcomm)
 * - GPU fallback via OpenCL/Vulkan
 * - 4x faster inference vs MediaPipe
 * - Streaming token generation
 *
 * Uses .litertlm model files from HuggingFace:
 *   litert-community/gemma-4-E2B-it-litert-lm
 *
 * Dependency: com.google.ai.edge.litertlm:litertlm-android
 * (which transitively depends on com.google.ai.edge.litert:litert)
 */
class LiteRtLmBackend(private val context: Context) : LlmBackend {

    companion object {
        private const val TAG = "LiteRtLmBackend"
        private const val MAX_TOKENS = 1024
        private const val TEMPERATURE = 0.7f
        private const val TOP_K = 40

        // Known class names for the LiteRT-LM API
        // The actual package may vary between releases, so we try multiple
        private val ENGINE_CLASS_CANDIDATES = listOf(
            "com.google.ai.edge.litertlm.LlmEngine",
            "com.google.ai.edge.litert.lm.LlmEngine",
            "com.google.ai.edge.litertlm.LlmInference",
        )
    }

    override val name = "LiteRT-LM"

    private var engine: Any? = null
    private var generateMethod: java.lang.reflect.Method? = null
    private var closeMethod: java.lang.reflect.Method? = null
    private var resolvedEngineClassName: String? = null

    override suspend fun load(modelPath: String) = withContext(Dispatchers.IO) {
        // Find the available engine class
        val (engineClass, className) = findEngineClass()
            ?: throw ClassNotFoundException(
                "LiteRT-LM engine class not found. Tried: ${ENGINE_CLASS_CANDIDATES.joinToString()}"
            )
        resolvedEngineClassName = className

        try {
            Log.d(TAG, "Using LiteRT-LM engine class: $className")

            // Build options
            val builderClass = Class.forName("$className\$Options\$Builder")
            val builder = builderClass.getDeclaredConstructor().newInstance()

            builderClass.getMethod("setModelPath", String::class.java)
                .invoke(builder, modelPath)
            builderClass.getMethod("setMaxTokens", Int::class.javaPrimitiveType)
                .invoke(builder, MAX_TOKENS)
            builderClass.getMethod("setTemperature", Float::class.javaPrimitiveType)
                .invoke(builder, TEMPERATURE)
            builderClass.getMethod("setTopK", Int::class.javaPrimitiveType)
                .invoke(builder, TOP_K)

            val options = builderClass.getMethod("build").invoke(builder)
            val optionsClass = Class.forName("$className\$Options")

            // Create engine
            engine = engineClass.getMethod("create", Context::class.java, optionsClass)
                .invoke(null, context, options)

            generateMethod = engineClass.getMethod("generateResponse", String::class.java)
            closeMethod = engineClass.getMethod("close")

            Log.d(TAG, "LiteRT-LM engine loaded: $modelPath")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize LiteRT-LM ($className)", e)
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

    /**
     * Check if the LiteRT-LM library is available on the classpath.
     */
    fun isAvailable(): Boolean = findEngineClass() != null

    private fun findEngineClass(): Pair<Class<*>, String>? {
        for (className in ENGINE_CLASS_CANDIDATES) {
            try {
                val clazz = Class.forName(className)
                return clazz to className
            } catch (_: ClassNotFoundException) {
                continue
            }
        }
        return null
    }
}
