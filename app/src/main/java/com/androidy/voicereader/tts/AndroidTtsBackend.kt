package com.androidy.voicereader.tts

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Standard Android TTS backend using the system TextToSpeech engine.
 */
class AndroidTtsBackend(private val context: Context) : TtsBackend {

    companion object {
        private const val TAG = "AndroidTtsBackend"
    }

    override val name = "Android TTS"
    override val isAvailable = true

    private var tts: TextToSpeech? = null
    private var onCompleted: ((String) -> Unit)? = null
    private var onStarted: ((String) -> Unit)? = null

    override suspend fun initialize() = suspendCoroutine { cont ->
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        utteranceId?.let { onStarted?.invoke(it) }
                    }
                    override fun onDone(utteranceId: String?) {
                        utteranceId?.let { onCompleted?.invoke(it) }
                    }
                    @Deprecated("Deprecated in API")
                    override fun onError(utteranceId: String?) {
                        Log.e(TAG, "TTS error: $utteranceId")
                        utteranceId?.let { onCompleted?.invoke(it) }
                    }
                })
                Log.d(TAG, "Android TTS initialized")
            }
            cont.resume(Unit)
        }
    }

    override fun speak(text: String, utteranceId: String, rate: Float, pitch: Float) {
        val engine = tts ?: return
        engine.setSpeechRate(rate)
        engine.setPitch(pitch)
        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
        }
        engine.speak(text, TextToSpeech.QUEUE_ADD, params, utteranceId)
    }

    override fun stop() {
        tts?.stop()
    }

    override fun release() {
        tts?.shutdown()
        tts = null
    }

    override fun setOnUtteranceCompleted(callback: (String) -> Unit) {
        onCompleted = callback
    }

    override fun setOnUtteranceStarted(callback: (String) -> Unit) {
        onStarted = callback
    }
}
