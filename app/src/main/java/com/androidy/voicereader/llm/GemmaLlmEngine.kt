package com.androidy.voicereader.llm

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * On-device LLM engine for text analysis, targeting Gemma 4 E2B.
 *
 * Uses a two-tier backend strategy:
 *   1. LiteRT-LM (preferred) — Google's latest runtime with NPU/GPU acceleration
 *   2. MediaPipe LLM Inference (fallback) — well-tested, wider device support
 *
 * Supported model files (checked in order of preference):
 *   - gemma-4-e2b-it.litertlm    (LiteRT-LM format, ~1.3GB)
 *   - gemma-4-e2b-it.task         (MediaPipe format, ~1.3GB)
 *   - gemma-2b-it-gpu-int4.bin    (legacy Gemma 2B, still works)
 *
 * Download from HuggingFace: litert-community/gemma-4-E2B-it-litert-lm
 * Or from Kaggle: google/gemma-4/transformers/gemma-4-e2b-it
 */
@Singleton
class GemmaLlmEngine @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "GemmaLlmEngine"

        // Model files checked in priority order
        // .task (MediaPipe, safest) > .litertlm (LiteRT-LM, fastest) > .tflite > .bin
        private val MODEL_FILES = listOf(
            "gemma-4-E2B-it-web.task",   // Gemma 4 E2B — MediaPipe task format (safest)
            "gemma-4-E2B-it.task",       // Gemma 4 E2B — MediaPipe task format
            "gemma-4-e2b-it.task",       // Gemma 4 E2B — lowercase variant
            "gemma-4-E2B-it.litertlm",  // Gemma 4 E2B — LiteRT-LM format (NPU accelerated)
            "gemma-4-e2b-it.litertlm",  // Gemma 4 E2B — lowercase variant
            "gemma-4-E2B-it.tflite",    // Gemma 4 E2B — TFLite/LiteRT format
            "gemma-4-e2b-it.tflite",    // Gemma 4 E2B — lowercase variant
            "gemma-4-E4B-it.task",      // Gemma 4 E4B — MediaPipe format
            "gemma-4-E4B-it.litertlm",  // Gemma 4 E4B — LiteRT-LM format
            "gemma-4-e4b-it.litertlm",  // Gemma 4 E4B — lowercase variant
            "gemma-4-E4B-it.tflite",    // Gemma 4 E4B — TFLite/LiteRT format
            "gemma-2b-it-gpu-int4.bin",  // Legacy Gemma 2B — still supported
        )
    }

    private var backend: LlmBackend? = null

    private val _isModelLoaded = MutableStateFlow(false)
    val isModelLoaded: StateFlow<Boolean> = _isModelLoaded

    private val _loadingProgress = MutableStateFlow("")
    val loadingProgress: StateFlow<String> = _loadingProgress

    private val _activeBackend = MutableStateFlow("None")
    val activeBackend: StateFlow<String> = _activeBackend

    private val _activeModel = MutableStateFlow("None")
    val activeModel: StateFlow<String> = _activeModel

    /**
     * Initialize the best available model + backend combo.
     * Tries LiteRT-LM first (NPU-accelerated), falls back to MediaPipe.
     */
    suspend fun initialize() = withContext(Dispatchers.IO) {
        try {
            _loadingProgress.value = "Scanning for model files..."

            val (modelPath, modelFile) = findBestModel()
                ?: run {
                    _loadingProgress.value = "No model found. Place a Gemma model file in the app's files directory.\n" +
                        "Supported: ${MODEL_FILES.joinToString(", ")}"
                    Log.e(TAG, "No model file found in any search path")
                    return@withContext
                }

            _activeModel.value = modelFile
            Log.d(TAG, "Found model: $modelFile at $modelPath")

            // Choose backend based on model format
            // .task/.bin → MediaPipe (safe, well-tested)
            // .litertlm → LiteRT-LM only (native format, may crash on version mismatch)
            // .tflite → LiteRT-LM (GPU delegate)

            if (modelFile.endsWith(".task") || modelFile.endsWith(".bin")) {
                // MediaPipe handles .task and .bin natively
                _loadingProgress.value = "Loading $modelFile via MediaPipe..."
                val mediaPipeBackend = MediaPipeBackend(context)
                try {
                    mediaPipeBackend.load(modelPath)
                    backend = mediaPipeBackend
                    _activeBackend.value = "MediaPipe"
                    _isModelLoaded.value = true
                    _loadingProgress.value = "Loaded $modelFile via MediaPipe"
                    Log.d(TAG, "Model loaded via MediaPipe")
                    return@withContext
                } catch (e: Exception) {
                    _loadingProgress.value = "Failed to load model: ${e.message}"
                    Log.e(TAG, "MediaPipe failed to load model", e)
                    mediaPipeBackend.close()
                }
            } else if (modelFile.endsWith(".litertlm") || modelFile.endsWith(".tflite")) {
                // LiteRT-LM handles .litertlm and .tflite
                val liteRtBackend = LiteRtLmBackend(context)
                if (liteRtBackend.isAvailable()) {
                    val accelType = if (modelFile.endsWith(".litertlm")) "NPU/GPU" else "GPU (LiteRT)"
                    _loadingProgress.value = "Loading $modelFile via LiteRT-LM ($accelType)..."
                    try {
                        liteRtBackend.load(modelPath)
                        backend = liteRtBackend
                        _activeBackend.value = "LiteRT-LM"
                        _isModelLoaded.value = true
                        _loadingProgress.value = "Loaded $modelFile via LiteRT-LM ($accelType)"
                        Log.d(TAG, "Model loaded via LiteRT-LM ($accelType)")
                        return@withContext
                    } catch (e: Exception) {
                        Log.e(TAG, "LiteRT-LM failed to load model", e)
                        liteRtBackend.close()
                        _loadingProgress.value = "LiteRT-LM failed: ${e.message}\nTry downloading the MediaPipe (.task) format instead."
                    }
                } else {
                    _loadingProgress.value = "LiteRT-LM runtime not available on this device.\nDownload the MediaPipe (.task) format instead."
                    Log.e(TAG, "LiteRT-LM not available for .litertlm model")
                }
            } else {
                _loadingProgress.value = "Unsupported model format: $modelFile"
                Log.e(TAG, "Unsupported model format: $modelFile")
            }
        } catch (e: Exception) {
            _loadingProgress.value = "Failed to initialize: ${e.message}"
            Log.e(TAG, "Initialization failed", e)
        }
    }

    /**
     * Analyze extracted text and produce annotated reading instructions.
     */
    suspend fun analyzeForReading(
        rawText: String,
        userCommand: String
    ): AnnotatedReadingResult = withContext(Dispatchers.IO) {
        val activeBackend = backend
            ?: return@withContext AnnotatedReadingResult.error("Model not loaded")

        val prompt = buildAnalysisPrompt(rawText, userCommand)

        try {
            val response = activeBackend.generateResponse(prompt)
            parseAnnotatedResponse(response)
        } catch (e: Exception) {
            Log.e(TAG, "Inference failed on ${activeBackend.name}", e)
            AnnotatedReadingResult.error("Analysis failed: ${e.message}")
        }
    }

    // --- Prompt building ---

    private fun buildAnalysisPrompt(rawText: String, userCommand: String): String {
        val truncatedText = if (rawText.length > 4000) {
            rawText.take(4000) + "\n[...text truncated...]"
        } else {
            rawText
        }

        // Gemma 4 has a larger context window and better instruction following than 2B,
        // so we can use a more detailed prompt and expect cleaner JSON output
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

    // --- Response parsing (JSON primary, marker fallback) ---

    private fun parseAnnotatedResponse(response: String): AnnotatedReadingResult {
        // Try JSON parsing first
        val jsonResult = tryParseJson("[" + response.trimStart().removePrefix("["))
        if (jsonResult != null && jsonResult.isNotEmpty()) {
            return AnnotatedReadingResult(segments = jsonResult, error = null)
        }

        // Fallback: parse marker-based format
        return parseMarkerFormat(response)
    }

    private fun tryParseJson(response: String): List<ReadingSegment>? {
        return try {
            val segments = mutableListOf<ReadingSegment>()
            val objectPattern = Regex("""\{\s*"text"\s*:\s*"([^"]*(?:\\"[^"]*)*)"\s*,\s*"type"\s*:\s*"(\w+)"[^}]*\}""")

            for (match in objectPattern.findAll(response)) {
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
            Log.d(TAG, "JSON parsing failed: ${e.message}")
            null
        }
    }

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

        if (segments.isEmpty() && response.isNotBlank()) {
            segments.add(ReadingSegment(SegmentType.NORMAL, response.trim()))
        }

        return AnnotatedReadingResult(segments = segments, error = null)
    }

    // --- Model file discovery ---

    /**
     * Search for the best available model file across multiple directories.
     * Returns (absolutePath, filename) or null if nothing found.
     */
    private fun findBestModel(): Pair<String, String>? {
        val searchDirs = listOf(
            context.filesDir,
            File(context.filesDir, "models"),
            context.getExternalFilesDir(null),
            context.getExternalFilesDir("models"),
        )

        // Check each model file in priority order across all directories
        for (modelFile in MODEL_FILES) {
            for (dir in searchDirs) {
                if (dir == null) continue
                val file = File(dir, modelFile)
                if (file.exists() && file.length() > 0) {
                    return file.absolutePath to modelFile
                }
            }
        }

        return null
    }

    fun release() {
        backend?.close()
        backend = null
        _isModelLoaded.value = false
        _activeBackend.value = "None"
        _activeModel.value = "None"
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
