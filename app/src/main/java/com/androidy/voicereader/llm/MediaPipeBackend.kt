package com.androidy.voicereader.llm

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * MediaPipe LLM Inference backend — the established on-device inference API.
 * Supports Gemma 2B, 3, and 4 via .task model files.
 *
 * This is the fallback backend when LiteRT-LM is not available.
 * Still functional and well-tested, just no NPU acceleration.
 */
class MediaPipeBackend(private val context: Context) : LlmBackend {

    companion object {
        private const val TAG = "MediaPipeBackend"
        private const val MAX_TOKENS = 1024
        private const val TEMPERATURE = 0.7f
        private const val TOP_K = 40
    }

    override val name = "MediaPipe"

    private var llmInference: LlmInference? = null

    override suspend fun load(modelPath: String) {
        withContext(Dispatchers.IO) {
            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelPath)
                .setMaxTokens(MAX_TOKENS)
                .build()

            llmInference = LlmInference.createFromOptions(context, options)
            Log.d(TAG, "MediaPipe LLM Inference loaded successfully with model: $modelPath")
        }
    }

    override suspend fun generateResponse(prompt: String): String = withContext(Dispatchers.IO) {
        val inference = llmInference
            ?: throw IllegalStateException("MediaPipe not loaded")
        inference.generateResponse(prompt)
    }

    override fun close() {
        llmInference?.close()
        llmInference = null
    }
}
