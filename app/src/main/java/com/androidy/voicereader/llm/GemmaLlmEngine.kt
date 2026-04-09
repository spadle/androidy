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

Analyze this text and produce an annotated version for text-to-speech reading.

Return a JSON array where each element is: {"text": "content", "type": "emphasis|normal|skip|summary|note"}

Types:
- "emphasis": Important content — read slowly and clearly
- "normal": Regular content at normal pace
- "skip": Filler, ads, navigation, timestamps, like counts — omit entirely
- "summary": Your brief summary of skipped or long sections
- "note": Your own commentary or context to help the listener

Rules:
- Focus on the main content the user cares about
- Skip navigation elements, ads, UI chrome, share buttons
- Add brief notes to provide context where helpful
- For long posts, summarize key points first then include important parts
- Keep output concise and suitable for listening

Return ONLY the JSON array, no other text.
<end_of_turn>
<start_of_turn>model
["""
    }

    private fun parseAnnotatedResponse(response: String): AnnotatedReadingResult {
        // Try JSON parsing first
        val jsonResult = tryParseJson("[" + response.trimStart().removePrefix("["))
        if (jsonResult != null && jsonResult.isNotEmpty()) {
            return AnnotatedReadingResult(segments = jsonResult, error = null)
        }

        // Fallback: parse marker-based format ([IMPORTANT], [SKIP], etc.)
        return parseMarkerFormat(response)
    }

    /**
     * Try to parse JSON array response: [{"text": "...", "type": "..."}]
     * Gemma 2B may produce slightly malformed JSON, so we use regex extraction as fallback.
     */
    private fun tryParseJson(response: String): List<ReadingSegment>? {
        return try {
            val segments = mutableListOf<ReadingSegment>()
            // Extract JSON objects using regex — more forgiving than strict JSON parsing
            val objectPattern = Regex("""\{\s*"text"\s*:\s*"([^"]*(?:\\"[^"]*)*)"\s*,\s*"type"\s*:\s*"(\w+)"[^}]*\}""")

            val matches = objectPattern.findAll(response)
            for (match in matches) {
                val text = match.groupValues[1]
                    .replace("\\\"", "\"")
                    .replace("\\n", "\n")
                    .trim()
                val typeStr = match.groupValues[2].lowercase()

                if (text.isBlank()) continue

                val type = when (typeStr) {
                    "emphasis", "important" -> SegmentType.IMPORTANT
                    "normal" -> SegmentType.NORMAL
                    "skip", "filler" -> SegmentType.SKIP
                    "summary" -> SegmentType.SUMMARY
                    "note", "commentary" -> SegmentType.NOTE
                    else -> SegmentType.NORMAL
                }
                segments.add(ReadingSegment(type, text))
            }

            if (segments.isEmpty()) null else segments
        } catch (e: Exception) {
            Log.d(TAG, "JSON parsing failed, using marker fallback: ${e.message}")
            null
        }
    }

    /**
     * Fallback parser for marker-based format:
     * [IMPORTANT] Some important text
     * [SKIP] Navigation stuff
     */
    private fun parseMarkerFormat(response: String): AnnotatedReadingResult {
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
                if (currentText.isNotBlank()) {
                    segments.add(ReadingSegment(currentType, currentText.toString().trim()))
                }
                currentType = newType
                currentText.clear()
                val content = trimmed.substringAfter("]").trim()
                if (content.isNotEmpty()) {
                    currentText.appendLine(content)
                }
            } else {
                currentText.appendLine(trimmed)
            }
        }

        if (currentText.isNotBlank()) {
            segments.add(ReadingSegment(currentType, currentText.toString().trim()))
        }

        // If no markers were found, treat the entire response as a single normal segment
        if (segments.isEmpty() && response.isNotBlank()) {
            segments.add(ReadingSegment(SegmentType.NORMAL, response.trim()))
        }

        return AnnotatedReadingResult(segments = segments, error = null)
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
