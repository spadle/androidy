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
import com.androidy.voicereader.data.ReadingHistoryDao
import com.androidy.voicereader.llm.GemmaLlmEngine
import com.androidy.voicereader.overlay.FloatingBubbleService
import com.androidy.voicereader.pipeline.ReadingPipeline
import com.androidy.voicereader.tts.IntelligentTtsEngine
import com.androidy.voicereader.ui.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import javax.inject.Inject

@AndroidEntryPoint
class VoiceAgentService : Service() {

    companion object {
        private const val TAG = "VoiceAgentService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "voice_reader_channel"

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning

        fun start(context: Context) {
            context.startForegroundService(Intent(context, VoiceAgentService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, VoiceAgentService::class.java))
        }
    }

    inner class LocalBinder : Binder() {
        fun getService(): VoiceAgentService = this@VoiceAgentService
    }

    private val binder = LocalBinder()
    // Use Default dispatcher — pipeline work is CPU-bound, not UI
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var voiceListener: VoiceCommandListener? = null
    private var pipeline: ReadingPipeline? = null

    // Hilt-injected — survives activity death
    @Inject lateinit var llmEngine: GemmaLlmEngine
    @Inject lateinit var ttsEngine: IntelligentTtsEngine
    @Inject lateinit var historyDao: ReadingHistoryDao

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
        // Handle playback control commands immediately
        when (command.type) {
            CommandType.PAUSE -> {
                ttsEngine.pause()
                _agentStatus.value = "Paused"
                withContext(Dispatchers.Main) { updateNotification("Paused") }
                return
            }
            CommandType.RESUME -> {
                ttsEngine.resume(scope)
                _agentStatus.value = "Resumed reading"
                withContext(Dispatchers.Main) { updateNotification("Reading...") }
                return
            }
            CommandType.REPLAY -> {
                ttsEngine.replay(scope)
                _agentStatus.value = "Replaying"
                withContext(Dispatchers.Main) { updateNotification("Replaying...") }
                return
            }
            else -> { /* fall through to pipeline */ }
        }

        if (pipeline == null) {
            pipeline = ReadingPipeline(llmEngine, ttsEngine, historyDao)
        }

        _agentStatus.value = "Processing: \"${command.rawText}\""
        withContext(Dispatchers.Main) { updateNotification("Processing voice command...") }

        // Pause voice listening while processing
        voiceListener?.stopListening()

        try {
            pipeline?.execute(command, scope)

            // Observe pipeline state for bubble updates
            scope.launch {
                pipeline?.pipelineState?.collect { state ->
                    FloatingBubbleService.updateState(FloatingBubbleService.fromPipelineState(state))
                }
            }

            _agentStatus.value = "Done reading"
        } catch (e: Exception) {
            Log.e(TAG, "Pipeline error", e)
            _agentStatus.value = "Error: ${e.message}"
        } finally {
            withContext(Dispatchers.Main) { updateNotification("Listening for commands...") }

            // One-shot wait for TTS to finish, then resume listening — no leaked collector
            scope.launch {
                try {
                    ttsEngine.isSpeaking.first { !it }
                } catch (_: Exception) {}
                voiceListener?.startListening()
                _agentStatus.value = "Listening for voice commands"
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
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
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
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, createNotification(text))
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.d(TAG, "Task removed — stopping service")
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        voiceListener?.stopListening()
        voiceListener = null
        pipeline?.stop()
        pipeline = null
        ttsEngine.stop()
        scope.cancel()
        _isRunning.value = false
        _agentStatus.value = "Stopped"
        Log.d(TAG, "Service destroyed")
    }
}
