package com.androidy.voicereader.service

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Wraps Android's SpeechRecognizer to continuously listen for voice commands.
 * Detects trigger phrases like "hey reader", "read this", etc.
 */
class VoiceCommandListener(private val context: Context) {

    companion object {
        private const val TAG = "VoiceCommandListener"

        // Trigger phrases that activate the reader
        val TRIGGER_PHRASES = listOf(
            "read this",
            "read it",
            "read the post",
            "read for me",
            "what does it say",
            "summarize this",
            "summarize it",
            "tell me what it says",
            "read the screen",
            "hey reader",
            "pause",
            "stop reading",
            "resume",
            "continue reading",
            "keep reading",
            "replay",
            "read again",
            "read it again"
        )
    }

    private var speechRecognizer: SpeechRecognizer? = null

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening

    private val _voiceCommand = MutableSharedFlow<VoiceCommand>(replay = 1)
    val voiceCommand: SharedFlow<VoiceCommand> = _voiceCommand

    private val _lastHeardText = MutableStateFlow("")
    val lastHeardText: StateFlow<String> = _lastHeardText

    private var shouldRestart = true

    fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.e(TAG, "Speech recognition not available on this device")
            return
        }

        shouldRestart = true
        initializeRecognizer()
        startRecognition()
    }

    fun stopListening() {
        shouldRestart = false
        _isListening.value = false
        speechRecognizer?.cancel()
        speechRecognizer?.destroy()
        speechRecognizer = null
    }

    private fun initializeRecognizer() {
        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(createRecognitionListener())
        }
    }

    private fun startRecognition() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1000L)
        }

        try {
            speechRecognizer?.startListening(intent)
            _isListening.value = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start listening", e)
            _isListening.value = false
        }
    }

    private fun createRecognitionListener() = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            Log.d(TAG, "Ready for speech")
            _isListening.value = true
        }

        override fun onBeginningOfSpeech() {
            Log.d(TAG, "Speech started")
        }

        override fun onRmsChanged(rmsdB: Float) {}

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            Log.d(TAG, "Speech ended")
        }

        override fun onError(error: Int) {
            val errorMsg = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH -> "No match"
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Speech timeout"
                SpeechRecognizer.ERROR_AUDIO -> "Audio error"
                SpeechRecognizer.ERROR_CLIENT -> "Client error"
                SpeechRecognizer.ERROR_NETWORK -> "Network error"
                SpeechRecognizer.ERROR_SERVER -> "Server error"
                else -> "Unknown error ($error)"
            }
            Log.d(TAG, "Recognition error: $errorMsg")

            // Auto-restart listening unless it's a fatal error
            if (shouldRestart && error != SpeechRecognizer.ERROR_CLIENT) {
                restartListening()
            }
        }

        override fun onResults(results: Bundle?) {
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            processResults(matches)

            // Restart listening for next command
            if (shouldRestart) {
                restartListening()
            }
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

        // Check if it matches any trigger phrase
        val matchedTrigger = TRIGGER_PHRASES.find { trigger ->
            bestMatch.contains(trigger)
        }

        if (matchedTrigger != null) {
            val command = VoiceCommand(
                rawText = bestMatch,
                trigger = matchedTrigger,
                type = classifyCommand(matchedTrigger),
                additionalContext = bestMatch.replace(matchedTrigger, "").trim()
            )
            _voiceCommand.tryEmit(command)
            Log.d(TAG, "Command detected: ${command.type} - ${command.rawText}")
        }
    }

    private fun classifyCommand(trigger: String): CommandType {
        return when {
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

    private fun restartListening() {
        speechRecognizer?.cancel()
        // Small delay before restarting to avoid rapid cycling
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (shouldRestart) {
                initializeRecognizer()
                startRecognition()
            }
        }, 500)
    }
}

data class VoiceCommand(
    val rawText: String,
    val trigger: String,
    val type: CommandType,
    val additionalContext: String
)

enum class CommandType {
    READ,       // Read the screen content
    SUMMARIZE,  // Provide a summary
    ACTIVATE,   // Wake word detected
    PAUSE,      // Pause current reading
    RESUME,     // Resume paused reading
    REPLAY      // Replay last reading
}
