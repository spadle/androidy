package com.androidy.voicereader.tts

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.androidy.voicereader.llm.AnnotatedReadingResult
import com.androidy.voicereader.llm.ReadingSegment
import com.androidy.voicereader.llm.SegmentType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Intelligent TTS engine that reads annotated text with varying voice properties.
 *
 * - IMPORTANT segments: slower speech rate, slightly lower pitch for gravitas
 * - NORMAL segments: standard speech rate
 * - NOTE segments: slightly faster, different pitch to distinguish from content
 * - SUMMARY segments: moderate pace, neutral tone
 * - SKIP segments: not spoken at all
 */
@Singleton
class IntelligentTtsEngine @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "IntelligentTTS"

        // Speech rate constants (1.0 = normal)
        private const val RATE_IMPORTANT = 0.85f
        private const val RATE_NORMAL = 1.0f
        private const val RATE_NOTE = 1.1f
        private const val RATE_SUMMARY = 0.95f

        // Pitch constants (1.0 = normal)
        private const val PITCH_IMPORTANT = 0.9f
        private const val PITCH_NORMAL = 1.0f
        private const val PITCH_NOTE = 1.15f
        private const val PITCH_SUMMARY = 1.0f
    }

    private var tts: TextToSpeech? = null
    private var isInitialized = false
    private var ssmlSupported = true // assume supported; fallback on error
    private val utteranceQueue = ConcurrentLinkedQueue<String>()

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking

    private val _currentSegmentIndex = MutableStateFlow(-1)
    val currentSegmentIndex: StateFlow<Int> = _currentSegmentIndex

    private var currentJob: Job? = null

    /** Initialize the TTS engine. */
    suspend fun initialize() = suspendCoroutine { cont ->
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        _isSpeaking.value = true
                    }

                    override fun onDone(utteranceId: String?) {
                        if (utteranceQueue.isEmpty()) {
                            _isSpeaking.value = false
                        }
                    }

                    @Deprecated("Deprecated in API")
                    override fun onError(utteranceId: String?) {
                        Log.e(TAG, "TTS error for utterance: $utteranceId")
                    }
                })
                isInitialized = true
                Log.d(TAG, "TTS initialized successfully")
            } else {
                Log.e(TAG, "TTS initialization failed with status: $status")
            }
            cont.resume(Unit)
        }
    }

    /**
     * Read an annotated result with intelligent voice modulation.
     * Each segment type gets different speech parameters.
     */
    suspend fun speakAnnotatedResult(
        result: AnnotatedReadingResult,
        scope: CoroutineScope
    ) {
        if (!isInitialized) {
            Log.e(TAG, "TTS not initialized")
            return
        }

        stop() // Stop any current speech

        currentJob = scope.launch {
            val readable = result.readableSegments

            for ((index, segment) in readable.withIndex()) {
                if (!isActive) break

                _currentSegmentIndex.value = index
                speakSegment(segment, "utterance_$index")

                // Wait for this utterance to complete before starting next
                waitForUtteranceComplete("utterance_$index")

                // Small pause between segments for natural flow
                if (index < readable.size - 1) {
                    delay(getPauseDuration(segment.type, readable.getOrNull(index + 1)?.type))
                }
            }

            _currentSegmentIndex.value = -1
            _isSpeaking.value = false
        }
    }

    private fun speakSegment(segment: ReadingSegment, utteranceId: String) {
        val engine = tts ?: return
        if (segment.type == SegmentType.SKIP) return

        // Try SSML first for richer voice control (prosody, emphasis, breaks)
        if (ssmlSupported) {
            val ssml = SsmlRenderer.renderSegment(segment, useSsml = true)
            if (ssml.isNotBlank()) {
                // Reset to defaults — SSML handles rate/pitch inline
                engine.setSpeechRate(RATE_NORMAL)
                engine.setPitch(PITCH_NORMAL)

                val params = Bundle().apply {
                    putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
                }
                val result = engine.speak(ssml, TextToSpeech.QUEUE_ADD, params, utteranceId)
                if (result == TextToSpeech.SUCCESS) {
                    utteranceQueue.add(utteranceId)
                    return
                }
                // SSML failed — fall back to plain mode for all future segments
                Log.w(TAG, "SSML not supported by TTS engine, falling back to plain mode")
                ssmlSupported = false
            }
        }

        // Fallback: set rate/pitch per segment type and use plain text
        when (segment.type) {
            SegmentType.IMPORTANT -> {
                engine.setSpeechRate(RATE_IMPORTANT)
                engine.setPitch(PITCH_IMPORTANT)
            }
            SegmentType.NORMAL -> {
                engine.setSpeechRate(RATE_NORMAL)
                engine.setPitch(PITCH_NORMAL)
            }
            SegmentType.NOTE -> {
                engine.setSpeechRate(RATE_NOTE)
                engine.setPitch(PITCH_NOTE)
            }
            SegmentType.SUMMARY -> {
                engine.setSpeechRate(RATE_SUMMARY)
                engine.setPitch(PITCH_SUMMARY)
            }
            SegmentType.SKIP -> return
        }

        val textToSpeak = SsmlRenderer.renderSegment(segment, useSsml = false)
        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
        }

        engine.speak(textToSpeak, TextToSpeech.QUEUE_ADD, params, utteranceId)
        utteranceQueue.add(utteranceId)
    }

    private suspend fun waitForUtteranceComplete(utteranceId: String) = suspendCancellableCoroutine { cont ->
        val listener = object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) {
                if (id == utteranceId) {
                    utteranceQueue.remove(utteranceId)
                    tts?.setOnUtteranceProgressListener(null)
                    if (cont.isActive) cont.resume(Unit)
                }
            }
            override fun onError(id: String?) {
                if (id == utteranceId) {
                    utteranceQueue.remove(utteranceId)
                    tts?.setOnUtteranceProgressListener(null)
                    if (cont.isActive) cont.resume(Unit)
                }
            }
            @Deprecated("Deprecated in API")
            override fun onError(utteranceId: String?, errorCode: Int) {
                onError(utteranceId)
            }
        }
        tts?.setOnUtteranceProgressListener(listener)

        cont.invokeOnCancellation {
            tts?.setOnUtteranceProgressListener(null)
        }
    }

    /** Get appropriate pause duration between segment types. */
    private fun getPauseDuration(current: SegmentType, next: SegmentType?): Long {
        if (next == null) return 0
        return when {
            current == SegmentType.IMPORTANT -> 500L
            current == SegmentType.NOTE -> 400L
            current == SegmentType.SUMMARY -> 600L
            next == SegmentType.IMPORTANT -> 300L
            else -> 200L
        }
    }

    fun stop() {
        currentJob?.cancel()
        currentJob = null
        tts?.stop()
        utteranceQueue.clear()
        _isSpeaking.value = false
        _currentSegmentIndex.value = -1
    }

    fun release() {
        stop()
        tts?.shutdown()
        tts = null
        isInitialized = false
    }
}
