package com.androidy.voicereader.service

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

class VoiceCommandListener(private val context: Context) {

    companion object {
        private const val TAG = "VoiceCommandListener"
        private const val RESTART_DELAY_MS = 2500L

        val TRIGGER_PHRASES = listOf(
            "read this", "read it", "read the post", "read for me",
            "what does it say", "summarize this", "summarize it",
            "tell me what it says", "read the screen", "hey reader",
            "pause", "stop reading", "resume", "continue reading",
            "keep reading", "replay", "read again", "read it again"
        )
    }

    private var speechRecognizer: SpeechRecognizer? = null
    private val handler = Handler(Looper.getMainLooper())

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening

    private val _voiceCommand = MutableSharedFlow<VoiceCommand>(replay = 1)
    val voiceCommand: SharedFlow<VoiceCommand> = _voiceCommand

    private val _lastHeardText = MutableStateFlow("")
    val lastHeardText: StateFlow<String> = _lastHeardText

    @Volatile private var shouldRestart = true
    @Volatile private var isRecognizerBusy = false
    @Volatile private var isDestroyed = false

    private val restartRunnable = Runnable {
        if (shouldRestart && !isDestroyed) {
            startRecognition()
        }
    }

    fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.e(TAG, "Speech recognition not available")
            return
        }

        shouldRestart = true
        isRecognizerBusy = false
        isDestroyed = false

        handler.post {
            if (speechRecognizer == null) {
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                    setRecognitionListener(createRecognitionListener())
                }
            }
            startRecognition()
        }
    }

    fun stopListening() {
        shouldRestart = false
        isDestroyed = true
        _isListening.value = false
        handler.removeCallbacks(restartRunnable)

        handler.post {
            try {
                speechRecognizer?.destroy()
            } catch (e: Exception) {
                Log.w(TAG, "Error destroying recognizer", e)
            }
            speechRecognizer = null
            isRecognizerBusy = false
        }
    }

    private fun startRecognition() {
        if (isRecognizerBusy || isDestroyed || speechRecognizer == null) return

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 3000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
        }

        try {
            isRecognizerBusy = true
            speechRecognizer?.startListening(intent)
            _isListening.value = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start listening", e)
            isRecognizerBusy = false
            _isListening.value = false
            scheduleRestart()
        }
    }

    private fun scheduleRestart() {
        if (shouldRestart && !isDestroyed) {
            handler.removeCallbacks(restartRunnable)
            handler.postDelayed(restartRunnable, RESTART_DELAY_MS)
        }
    }

    private fun createRecognitionListener() = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            _isListening.value = true
        }
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}

        override fun onError(error: Int) {
            isRecognizerBusy = false
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> scheduleRestart()
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                    // Wait longer before retry
                    if (shouldRestart && !isDestroyed) {
                        handler.removeCallbacks(restartRunnable)
                        handler.postDelayed(restartRunnable, RESTART_DELAY_MS * 2)
                    }
                }
                else -> {
                    Log.e(TAG, "Fatal recognition error: $error — stopping")
                    _isListening.value = false
                }
            }
        }

        override fun onResults(results: Bundle?) {
            isRecognizerBusy = false
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            processResults(matches)
            scheduleRestart()
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (!matches.isNullOrEmpty()) {
                _lastHeardText.value = matches[0]
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun processResults(matches: List<String>?) {
        if (matches.isNullOrEmpty()) return
        val bestMatch = matches[0].lowercase().trim()
        _lastHeardText.value = bestMatch
        Log.d(TAG, "Heard: $bestMatch")

        val matchedTrigger = TRIGGER_PHRASES.find { bestMatch.contains(it) }
        if (matchedTrigger != null) {
            val command = VoiceCommand(
                rawText = bestMatch,
                trigger = matchedTrigger,
                type = classifyCommand(matchedTrigger),
                additionalContext = bestMatch.replace(matchedTrigger, "").trim()
            )
            _voiceCommand.tryEmit(command)
            Log.d(TAG, "Command: ${command.type} - ${command.rawText}")
        }
    }

    private fun classifyCommand(trigger: String): CommandType = when {
        trigger.contains("pause") || trigger == "stop reading" -> CommandType.PAUSE
        trigger.contains("resume") || trigger.contains("continue") || trigger == "keep reading" -> CommandType.RESUME
        trigger.contains("replay") || trigger.contains("again") -> CommandType.REPLAY
        trigger.contains("summarize") -> CommandType.SUMMARIZE
        trigger.contains("read") -> CommandType.READ
        trigger.contains("tell") || trigger.contains("what") -> CommandType.READ
        trigger.contains("hey reader") -> CommandType.ACTIVATE
        else -> CommandType.READ
    }
}

data class VoiceCommand(
    val rawText: String,
    val trigger: String,
    val type: CommandType,
    val additionalContext: String
)

enum class CommandType {
    READ, SUMMARIZE, ACTIVATE, PAUSE, RESUME, REPLAY
}
