package com.androidy.voicereader.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * On-device TTS backend using a TFLite model for higher quality offline speech.
 *
 * This is a placeholder implementation that provides the architecture for
 * integrating a TFLite TTS model (e.g., FastSpeech2 or VITS).
 * The actual model inference requires a compatible .tflite model file.
 *
 * When a model is available, it converts text → mel spectrogram → audio waveform,
 * bypassing the system TTS entirely for better control and quality.
 */
class OnDeviceTtsBackend(private val context: Context) : TtsBackend {

    companion object {
        private const val TAG = "OnDeviceTtsBackend"
        private const val SAMPLE_RATE = 22050
        private const val MODEL_FILE = "tts_model.tflite"
        private val SUPPORTED_MODEL_NAMES = listOf(
            "tts_model.tflite",
            "vits.tflite",
            "fastspeech2.tflite"
        )
    }

    override val name = "On-Device TTS"
    override val isAvailable: Boolean
        get() = findModelFile() != null

    private var modelFile: File? = null
    private var audioTrack: AudioTrack? = null
    private var onCompleted: ((String) -> Unit)? = null
    private var onStarted: ((String) -> Unit)? = null
    private var initialized = false

    override suspend fun initialize() {
        withContext(Dispatchers.IO) {
            modelFile = findModelFile()
            if (modelFile != null) {
                Log.d(TAG, "Found TTS model: ${modelFile?.name}")
                // Model loading would happen here when a model file is available
                // Example: interpreter = Interpreter(modelFile!!)
                initialized = true
            } else {
                Log.d(TAG, "No on-device TTS model found. Searched for: $SUPPORTED_MODEL_NAMES")
            }
        }
    }

    override fun speak(text: String, utteranceId: String, rate: Float, pitch: Float) {
        if (!initialized || modelFile == null) {
            Log.w(TAG, "On-device TTS not initialized, skipping")
            onCompleted?.invoke(utteranceId)
            return
        }

        onStarted?.invoke(utteranceId)

        // Placeholder: In a real implementation, this would:
        // 1. Tokenize the input text
        // 2. Run the TFLite model to generate mel spectrograms
        // 3. Run a vocoder to convert mel → audio waveform
        // 4. Play the audio via AudioTrack
        //
        // For now, this signals completion immediately.
        // When you add a real TTS model, replace this with actual inference.
        Log.d(TAG, "On-device TTS would speak: ${text.take(50)}...")
        onCompleted?.invoke(utteranceId)
    }

    override fun stop() {
        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null
    }

    override fun release() {
        stop()
        initialized = false
    }

    override fun setOnUtteranceCompleted(callback: (String) -> Unit) {
        onCompleted = callback
    }

    override fun setOnUtteranceStarted(callback: (String) -> Unit) {
        onStarted = callback
    }

    private fun findModelFile(): File? {
        val dirs = listOf(
            context.filesDir,
            context.getExternalFilesDir(null)
        )

        for (dir in dirs) {
            if (dir == null) continue
            for (name in SUPPORTED_MODEL_NAMES) {
                val file = File(dir, name)
                if (file.exists() && file.length() > 0) {
                    return file
                }
            }
        }
        return null
    }

    /**
     * Play raw PCM audio data through AudioTrack.
     * Used when the TFLite model outputs raw audio samples.
     */
    private fun playAudio(audioData: FloatArray, sampleRate: Int = SAMPLE_RATE) {
        val bufferSize = audioData.size * 2 // 16-bit PCM
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .build()

        // Convert float to 16-bit PCM
        val pcmData = ShortArray(audioData.size) { i ->
            (audioData[i] * Short.MAX_VALUE).toInt().toShort()
        }

        audioTrack?.play()
        audioTrack?.write(pcmData, 0, pcmData.size)
    }
}
