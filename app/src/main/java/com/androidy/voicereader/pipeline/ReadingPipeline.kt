package com.androidy.voicereader.pipeline

import android.util.Log
import com.androidy.voicereader.accessibility.ExtractedContent
import com.androidy.voicereader.accessibility.ScreenReaderAccessibilityService
import com.androidy.voicereader.llm.AnnotatedReadingResult
import com.androidy.voicereader.llm.GemmaLlmEngine
import com.androidy.voicereader.service.CommandType
import com.androidy.voicereader.service.VoiceCommand
import com.androidy.voicereader.tts.IntelligentTtsEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Orchestrates the full reading pipeline:
 *   Voice Command → Screen Extraction → LLM Analysis → Intelligent TTS
 *
 * Each stage feeds into the next, with status updates along the way.
 */
class ReadingPipeline(
    private val llmEngine: GemmaLlmEngine,
    private val ttsEngine: IntelligentTtsEngine
) {
    companion object {
        private const val TAG = "ReadingPipeline"
        private const val EXTRACTION_TIMEOUT_MS = 15_000L
    }

    private val _pipelineState = MutableStateFlow(PipelineState.IDLE)
    val pipelineState: StateFlow<PipelineState> = _pipelineState

    private val _statusMessage = MutableStateFlow("")
    val statusMessage: StateFlow<String> = _statusMessage

    private var lastExtractedContent: ExtractedContent? = null
    private var lastAnalysis: AnnotatedReadingResult? = null

    /**
     * Execute the full pipeline for a voice command.
     */
    suspend fun execute(command: VoiceCommand, scope: CoroutineScope) {
        try {
            // Stage 1: Extract screen content
            _pipelineState.value = PipelineState.EXTRACTING
            _statusMessage.value = "Reading screen content..."
            Log.d(TAG, "Stage 1: Extracting screen content")

            val content = extractScreenContent()
            if (content == null || content.fullText.isBlank()) {
                _statusMessage.value = "Could not read screen content"
                _pipelineState.value = PipelineState.ERROR
                return
            }
            lastExtractedContent = content
            Log.d(TAG, "Extracted ${content.texts.size} text segments (${content.fullText.length} chars)")

            // Stage 2: LLM analysis
            _pipelineState.value = PipelineState.ANALYZING
            _statusMessage.value = "Analyzing content with AI..."
            Log.d(TAG, "Stage 2: Analyzing with Gemma")

            val userIntent = when (command.type) {
                CommandType.SUMMARIZE -> "Summarize the main points of this content. ${command.additionalContext}"
                CommandType.READ -> "Read this content intelligently, emphasizing important parts. ${command.additionalContext}"
                CommandType.ACTIVATE -> "Read the main content on screen. ${command.additionalContext}"
            }

            val analysis = llmEngine.analyzeForReading(content.fullText, userIntent)
            if (analysis.hasError) {
                _statusMessage.value = "Analysis failed: ${analysis.error}"
                _pipelineState.value = PipelineState.ERROR
                return
            }
            lastAnalysis = analysis
            Log.d(TAG, "Analysis produced ${analysis.segments.size} segments (${analysis.readableSegments.size} readable)")

            // Stage 3: Intelligent TTS
            _pipelineState.value = PipelineState.SPEAKING
            _statusMessage.value = "Reading aloud..."
            Log.d(TAG, "Stage 3: Speaking ${analysis.readableSegments.size} segments")

            ttsEngine.speakAnnotatedResult(analysis, scope)

            _pipelineState.value = PipelineState.IDLE
            _statusMessage.value = "Finished reading"

        } catch (e: Exception) {
            Log.e(TAG, "Pipeline error", e)
            _pipelineState.value = PipelineState.ERROR
            _statusMessage.value = "Error: ${e.message}"
        }
    }

    /**
     * Trigger screen extraction via the accessibility service and wait for results.
     */
    private suspend fun extractScreenContent(): ExtractedContent? {
        if (!ScreenReaderAccessibilityService.isConnected.value) {
            _statusMessage.value = "Accessibility service not enabled"
            return null
        }

        // Trigger extraction
        ScreenReaderAccessibilityService.requestExtraction()

        // Wait for the result with timeout
        return withTimeoutOrNull(EXTRACTION_TIMEOUT_MS) {
            ScreenReaderAccessibilityService.extractedText.first()
        }
    }

    fun stop() {
        ttsEngine.stop()
        _pipelineState.value = PipelineState.IDLE
        _statusMessage.value = "Stopped"
    }
}

enum class PipelineState {
    IDLE,
    EXTRACTING,
    ANALYZING,
    SPEAKING,
    ERROR
}
