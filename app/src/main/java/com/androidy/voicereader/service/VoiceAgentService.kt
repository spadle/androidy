package com.androidy.voicereader.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.androidy.voicereader.R
import com.androidy.voicereader.accessibility.ScreenReaderAccessibilityService
import com.androidy.voicereader.llm.GemmaLlmEngine
import com.androidy.voicereader.pipeline.ReadingPipeline
import com.androidy.voicereader.tts.IntelligentTtsEngine
import com.androidy.voicereader.ui.MainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Foreground service that keeps the voice agent running in the background.
 * Coordinates voice listening, screen extraction, LLM analysis, and TTS output.
 */
class VoiceAgentService : Service() {

    companion object {
        private const val TAG = "VoiceAgentService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "voice_reader_channel"

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning

        fun start(context: Context) {
            val intent = Intent(context, VoiceAgentService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, VoiceAgentService::class.java)
            context.stopService(intent)
        }
    }

    inner class LocalBinder : Binder() {
        fun getService(): VoiceAgentService = this@VoiceAgentService
    }

    private val binder = LocalBinder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var voiceListener: VoiceCommandListener? = null
    private var pipeline: ReadingPipeline? = null

    // These will be injected from the activity that binds
    var llmEngine: GemmaLlmEngine? = null
    var ttsEngine: IntelligentTtsEngine? = null

    private val _agentStatus = MutableStateFlow("Idle")
    val agentStatus: StateFlow<String> = _agentStatus

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        Log.d(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createNotification("Listening for commands..."))
        _isRunning.value = true

        startVoiceListening()
        observeCommands()

        return START_STICKY
    }

    private fun startVoiceListening() {
        voiceListener = VoiceCommandListener(this).apply {
            startListening()
        }
        _agentStatus.value = "Listening for voice commands"
        Log.d(TAG, "Voice listening started")
    }

    private fun observeCommands() {
        scope.launch {
            voiceListener?.voiceCommand?.collect { command ->
                Log.d(TAG, "Processing command: ${command.type}")
                handleCommand(command)
            }
        }
    }

    private suspend fun handleCommand(command: VoiceCommand) {
        val llm = llmEngine
        val tts = ttsEngine

        if (llm == null || tts == null) {
            _agentStatus.value = "Error: engines not initialized"
            return
        }

        if (pipeline == null) {
            pipeline = ReadingPipeline(llm, tts)
        }

        _agentStatus.value = "Processing: \"${command.rawText}\""
        updateNotification("Processing voice command...")

        // Pause voice listening while processing
        voiceListener?.stopListening()

        try {
            pipeline?.execute(command, scope)
            _agentStatus.value = "Done reading"
        } catch (e: Exception) {
            Log.e(TAG, "Pipeline error", e)
            _agentStatus.value = "Error: ${e.message}"
        } finally {
            updateNotification("Listening for commands...")
            // Resume listening after TTS finishes
            scope.launch {
                tts.isSpeaking.collect { speaking ->
                    if (!speaking) {
                        voiceListener?.startListening()
                        _agentStatus.value = "Listening for voice commands"
                    }
                }
            }
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_description)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun createNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Voice Reader")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, createNotification(text))
    }

    override fun onDestroy() {
        super.onDestroy()
        voiceListener?.stopListening()
        scope.cancel()
        _isRunning.value = false
        _agentStatus.value = "Stopped"
        Log.d(TAG, "Service destroyed")
    }
}
