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
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

@Singleton
class IntelligentTtsEngine @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "IntelligentTTS"
        private const val RATE_IMPORTANT = 0.85f
        private const val RATE_NORMAL = 1.0f
        private const val RATE_NOTE = 1.1f
        private const val RATE_SUMMARY = 0.95f
        private const val PITCH_IMPORTANT = 0.9f
        private const val PITCH_NORMAL = 1.0f
        private const val PITCH_NOTE = 1.15f
        private const val PITCH_SUMMARY = 1.0f
    }

    private var tts: TextToSpeech? = null
    private var isInitialized = false
    private var ssmlSupported = true

    // Single permanent map for utterance completion tracking — no listener swapping
    private val utteranceCompletions = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking

    private val _currentSegmentIndex = MutableStateFlow(-1)
    val currentSegmentIndex: StateFlow<Int> = _currentSegmentIndex

    private var currentJob: Job? = null
    private var lastResult: AnnotatedReadingResult? = null
    private var pausedAtIndex: Int = -1

    private val _isPaused = MutableStateFlow(false)
    val isPaused: StateFlow<Boolean> = _isPaused

    suspend fun initialize() = suspendCoroutine { cont ->
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                // Set ONE permanent listener — never replaced
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        _isSpeaking.value = true
                    }

                    override fun onDone(utteranceId: String?) {
                        utteranceId?.let { id ->
                            utteranceCompletions.remove(id)?.complete(Unit)
                        }
                        if (utteranceCompletions.isEmpty()) {
                            _isSpeaking.value = false
                        }
                    }

                    @Deprecated("Deprecated in API")
                    override fun onError(utteranceId: String?) {
                        Log.e(TAG, "TTS error for utterance: $utteranceId")
                        utteranceId?.let { id ->
                            utteranceCompletions.remove(id)?.complete(Unit)
                        }
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

    suspend fun speakAnnotatedResult(
        result: AnnotatedReadingResult,
        scope: CoroutineScope
    ) {
        if (!isInitialized) {
            Log.e(TAG, "TTS not initialized")
            return
        }

        stop()
        lastResult = result
        _isPaused.value = false

        currentJob = scope.launch {
            speakSegmentsFrom(result.readableSegments, 0)
        }
    }

    private suspend fun speakSegmentsFrom(segments: List<ReadingSegment>, startIndex: Int) {
        for (index in startIndex until segments.size) {
            if (!coroutineContext.isActive) break

            _currentSegmentIndex.value = index
            val segment = segments[index]
            val utteranceId = "utt_${index}_${System.nanoTime()}"

            speakSegment(segment, utteranceId)
            waitForUtteranceComplete(utteranceId)

            if (index < segments.size - 1) {
                delay(getPauseDuration(segment.type, segments.getOrNull(index + 1)?.type))
            }
        }

        _currentSegmentIndex.value = -1
        _isSpeaking.value = false
    }

    private fun speakSegment(segment: ReadingSegment, utteranceId: String) {
        val engine = tts ?: return
        if (segment.type == SegmentType.SKIP) return

        if (ssmlSupported) {
            val ssml = SsmlRenderer.renderSegment(segment, useSsml = true)
            if (ssml.isNotBlank()) {
                engine.setSpeechRate(RATE_NORMAL)
                engine.setPitch(PITCH_NORMAL)
                val params = Bundle().apply {
                    putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
                }
                val result = engine.speak(ssml, TextToSpeech.QUEUE_ADD, params, utteranceId)
                if (result == TextToSpeech.SUCCESS) return
                Log.w(TAG, "SSML not supported, falling back to plain mode")
                ssmlSupported = false
            }
        }

        when (segment.type) {
            SegmentType.IMPORTANT -> { engine.setSpeechRate(RATE_IMPORTANT); engine.setPitch(PITCH_IMPORTANT) }
            SegmentType.NORMAL -> { engine.setSpeechRate(RATE_NORMAL); engine.setPitch(PITCH_NORMAL) }
            SegmentType.NOTE -> { engine.setSpeechRate(RATE_NOTE); engine.setPitch(PITCH_NOTE) }
            SegmentType.SUMMARY -> { engine.setSpeechRate(RATE_SUMMARY); engine.setPitch(PITCH_SUMMARY) }
            SegmentType.SKIP -> return
        }

        val textToSpeak = SsmlRenderer.renderSegment(segment, useSsml = false)
        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
        }
        engine.speak(textToSpeak, TextToSpeech.QUEUE_ADD, params, utteranceId)
    }

    private suspend fun waitForUtteranceComplete(utteranceId: String) {
        val deferred = CompletableDeferred<Unit>()
        utteranceCompletions[utteranceId] = deferred
        try {
            withTimeout(30_000) { deferred.await() }
        } catch (e: TimeoutCancellationException) {
            utteranceCompletions.remove(utteranceId)
            Log.w(TAG, "Utterance timed out: $utteranceId")
        }
    }

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

    fun pause() {
        if (_isSpeaking.value && !_isPaused.value) {
            pausedAtIndex = _currentSegmentIndex.value
            currentJob?.cancel()
            currentJob = null
            tts?.stop()
            utteranceCompletions.clear()
            _isSpeaking.value = false
            _isPaused.value = true
            Log.d(TAG, "Paused at segment $pausedAtIndex")
        }
    }

    fun resume(scope: CoroutineScope) {
        val result = lastResult ?: return
        if (!_isPaused.value || pausedAtIndex < 0) return

        _isPaused.value = false
        val startIndex = pausedAtIndex

        currentJob = scope.launch {
            speakSegmentsFrom(result.readableSegments, startIndex)
        }
        Log.d(TAG, "Resumed from segment $startIndex")
    }

    fun replay(scope: CoroutineScope) {
        val result = lastResult ?: return
        _isPaused.value = false
        pausedAtIndex = -1
        scope.launch { speakAnnotatedResult(result, scope) }
    }

    fun stop() {
        currentJob?.cancel()
        currentJob = null
        tts?.stop()
        utteranceCompletions.values.forEach { it.complete(Unit) }
        utteranceCompletions.clear()
        _isSpeaking.value = false
        _isPaused.value = false
        _currentSegmentIndex.value = -1
        pausedAtIndex = -1
    }

    fun release() {
        stop()
        tts?.shutdown()
        tts = null
        isInitialized = false
    }
}
