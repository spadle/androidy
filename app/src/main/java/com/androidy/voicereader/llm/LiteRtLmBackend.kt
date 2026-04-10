package com.androidy.voicereader.llm

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * LiteRT-LM backend using reflection-based API for compatibility across versions.
 */
class LiteRtLmBackend(private val context: Context) : LlmBackend {

    companion object {
        private const val TAG = "LiteRtLmBackend"
        private const val MAX_TOKENS = 1024
        private const val TEMPERATURE = 0.7f
        private const val TOP_K = 40

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

    override suspend fun load(modelPath: String) {
        withContext(Dispatchers.IO) {
            val (engineClass, className) = findEngineClass()
                ?: throw ClassNotFoundException(
                    "LiteRT-LM engine class not found. Tried: ${ENGINE_CLASS_CANDIDATES.joinToString()}"
                )

            try {
                Log.d(TAG, "Using LiteRT-LM engine class: $className")

                val builderClass = Class.forName("$className\$Options\$Builder")
                val builder = builderClass.getDeclaredConstructor().newInstance()

                builderClass.getMethod("setModelPath", String::class.java)
                    .invoke(builder, modelPath)
                builderClass.getMethod("setMaxTokens", Int::class.javaPrimitiveType)
                    .invoke(builder, MAX_TOKENS)

                // Temperature and TopK may not exist in all versions — try gracefully
                try {
                    builderClass.getMethod("setTemperature", Float::class.javaPrimitiveType)
                        .invoke(builder, TEMPERATURE)
                } catch (_: NoSuchMethodException) {}
                try {
                    builderClass.getMethod("setTopK", Int::class.javaPrimitiveType)
                        .invoke(builder, TOP_K)
                } catch (_: NoSuchMethodException) {}

                val options = builderClass.getMethod("build").invoke(builder)
                val optionsClass = Class.forName("$className\$Options")

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

    fun isAvailable(): Boolean = findEngineClass() != null

    private fun findEngineClass(): Pair<Class<*>, String>? {
        for (className in ENGINE_CLASS_CANDIDATES) {
            try {
                return Class.forName(className) to className
            } catch (_: ClassNotFoundException) {
                continue
            }
        }
        return null
    }
}
