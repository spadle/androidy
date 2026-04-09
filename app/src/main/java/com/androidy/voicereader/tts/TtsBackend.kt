package com.androidy.voicereader.tts

/**
 * Abstraction for TTS backends.
 * Allows switching between Android built-in TTS and on-device TFLite models.
 */
interface TtsBackend {
    val name: String
    val isAvailable: Boolean

    suspend fun initialize()
    fun speak(text: String, utteranceId: String, rate: Float = 1.0f, pitch: Float = 1.0f)
    fun stop()
    fun release()
    fun setOnUtteranceCompleted(callback: (String) -> Unit)
    fun setOnUtteranceStarted(callback: (String) -> Unit)
}
