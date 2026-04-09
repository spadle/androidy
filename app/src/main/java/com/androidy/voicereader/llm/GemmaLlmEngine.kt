package com.androidy.voicereader.llm

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * On-device LLM engine using MediaPipe's LLM Inference API with Gemma 2B.
 *
 * The model file (gemma-2b-it-gpu-int4.bin) should be placed in the app's
 * files directory. Users can download it from Kaggle or HuggingFace.
 */
@Singleton
class GemmaLlmEngine @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "GemmaLlmEngine"
        private const val MODEL_FILENAME = "gemma-2b-it-gpu-int4.bin"
        private const val MAX_TOKENS = 1024
        private const val TEMPERATURE = 0.7f
        private const val TOP_K = 40
    }

    private var llmInference: LlmInference? = null

    private val _isModelLoaded = MutableStateFlow(false)
    val isModelLoaded: StateFlow<Boolean> = _isModelLoaded

    private val _loadingProgress = MutableStateFlow("")
    val loadingProgress: StateFlow<String> = _loadingProgress

    /** Initialize the Gemma model. Call this once on app startup. */
    suspend fun initialize() = withContext(Dispatchers.IO) {
        try {
            _loadingProgress.value = "Locating model file..."
            val modelPath = getModelPath()

            if (modelPath == null) {
                _loadingProgress.value = "Model not found. Please place $MODEL_FILENAME in app files."
                Log.e(TAG, "Model file not found")
                return@withContext
            }

            _loadingProgress.value = "Loading Gemma model..."
            Log.d(TAG, "Loading model from: $modelPath")

            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelPath)
                .setMaxTokens(MAX_TOKENS)
                .setTemperature(TEMPERATURE)
                .setTopK(TOP_K)
                .build()

            llmInference = LlmInference.createFromOptions(context, options)
            _isModelLoaded.value = true
            _loadingProgress.value = "Model loaded successfully"
            Log.d(TAG, "Gemma model loaded successfully")
        } catch (e: Exception) {
            _loadingProgress.value = "Failed to load model: ${e.message}"
            Log.e(TAG, "Failed to load Gemma model", e)
        }
    }

    /**
     * Analyze extracted text and produce annotated reading instructions.
     * Returns structured output that the TTS engine can interpret.
     */
    suspend fun analyzeForReading(
        rawText: String,
        userCommand: String
    ): AnnotatedReadingResult = withContext(Dispatchers.IO) {
        val inference = llmInference
            ?: return@withContext AnnotatedReadingResult.error("Model not loaded")

        val prompt = buildAnalysisPrompt(rawText, userCommand)

        try {
            val response = inference.generateResponse(prompt)
            parseAnnotatedResponse(response)
        } catch (e: Exception) {
            Log.e(TAG, "Inference failed", e)
            AnnotatedReadingResult.error("Analysis failed: ${e.message}")
        }
    }

    /** Stream a response token by token for real-time TTS. */
    fun analyzeForReadingStreaming(
        rawText: String,
        userCommand: String
    ): Flow<String> = flow {
        val inference = llmInference ?: run {
            emit("[ERROR: Model not loaded]")
            return@flow
        }

        val prompt = buildAnalysisPrompt(rawText, userCommand)

        try {
            inference.generateResponseAsync(prompt)
            // For non-streaming fallback, get the full response
            val response = inference.generateResponse(prompt)
            emit(response)
        } catch (e: Exception) {
            emit("[ERROR: ${e.message}]")
        }
    }

    private fun buildAnalysisPrompt(rawText: String, userCommand: String): String {
        // Truncate text if too long for the model's context
        val truncatedText = if (rawText.length > 3000) {
            rawText.take(3000) + "\n[...text truncated...]"
        } else {
            rawText
        }

        return """<start_of_turn>user
You are a smart reading assistant. The user wants you to read content from their screen.

User's command: "$userCommand"

Here is the text extracted from the screen:
---
$truncatedText
---

Analyze this text and produce an annotated version for text-to-speech reading. Use these markers:
- [IMPORTANT] before sections that should be read with emphasis (slower, clearer)
- [SKIP] before sections that are filler, ads, repetitive, or unimportant (these will be omitted)
- [SUMMARY] before your brief summary of skipped content
- [NOTE] before your own commentary or context about the content
- [NORMAL] before regular content to read at normal pace

Rules:
- Focus on the main content the user cares about
- Skip navigation elements, ads, timestamps, like counts, share buttons text
- Add brief notes to provide context where helpful
- If the text is a long post, summarize key points first then read important parts
- Keep your response concise and suitable for listening

Produce the annotated reading now.
<end_of_turn>
<start_of_turn>model
"""
    }

    private fun parseAnnotatedResponse(response: String): AnnotatedReadingResult {
        val segments = mutableListOf<ReadingSegment>()
        val lines = response.lines()

        var currentType = SegmentType.NORMAL
        val currentText = StringBuilder()

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue

            val newType = when {
                trimmed.startsWith("[IMPORTANT]") -> SegmentType.IMPORTANT
                trimmed.startsWith("[SKIP]") -> SegmentType.SKIP
                trimmed.startsWith("[SUMMARY]") -> SegmentType.SUMMARY
                trimmed.startsWith("[NOTE]") -> SegmentType.NOTE
                trimmed.startsWith("[NORMAL]") -> SegmentType.NORMAL
                else -> null
            }

            if (newType != null) {
                // Save previous segment
                if (currentText.isNotBlank()) {
                    segments.add(ReadingSegment(currentType, currentText.toString().trim()))
                }
                currentType = newType
                currentText.clear()
                // Remove the marker prefix from this line
                val content = trimmed.substringAfter("]").trim()
                if (content.isNotEmpty()) {
                    currentText.appendLine(content)
                }
            } else {
                currentText.appendLine(trimmed)
            }
        }

        // Don't forget the last segment
        if (currentText.isNotBlank()) {
            segments.add(ReadingSegment(currentType, currentText.toString().trim()))
        }

        return AnnotatedReadingResult(
            segments = segments,
            error = null
        )
    }

    private fun getModelPath(): String? {
        // Check app's internal files directory
        val internalFile = File(context.filesDir, MODEL_FILENAME)
        if (internalFile.exists()) return internalFile.absolutePath

        // Check external files directory
        val externalFile = File(context.getExternalFilesDir(null), MODEL_FILENAME)
        if (externalFile.exists()) return externalFile.absolutePath

        // Check the models subdirectory
        val modelsDir = File(context.filesDir, "models")
        val modelsFile = File(modelsDir, MODEL_FILENAME)
        if (modelsFile.exists()) return modelsFile.absolutePath

        return null
    }

    fun release() {
        llmInference?.close()
        llmInference = null
        _isModelLoaded.value = false
    }
}

data class AnnotatedReadingResult(
    val segments: List<ReadingSegment>,
    val error: String?
) {
    companion object {
        fun error(message: String) = AnnotatedReadingResult(emptyList(), message)
    }

    val hasError get() = error != null
    val readableSegments get() = segments.filter { it.type != SegmentType.SKIP }
}

data class ReadingSegment(
    val type: SegmentType,
    val text: String
)

enum class SegmentType {
    NORMAL,     // Read at normal pace
    IMPORTANT,  // Read with emphasis — slower, clearer
    SKIP,       // Omit entirely
    SUMMARY,    // Brief summary of skipped content
    NOTE        // LLM's own commentary
}
