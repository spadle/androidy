package com.androidy.voicereader.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.core.content.ContextCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.androidy.voicereader.data.ModelDownloadManager
import com.androidy.voicereader.service.VoiceAgentService
import com.androidy.voicereader.ui.theme.VoiceReaderTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private var voiceAgentService: VoiceAgentService? = null
    private var serviceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as VoiceAgentService.LocalBinder
            voiceAgentService = binder.getService().also {
                it.llmEngine = viewModel.llmEngine
                it.ttsEngine = viewModel.ttsEngine
                it.historyDao = viewModel.historyDao
            }
            serviceBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            voiceAgentService = null
            serviceBound = false
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* permissions granted or denied — UI will reflect state */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestPermissions()

        setContent {
            VoiceReaderTheme {
                val navController = rememberNavController()
                val uiState by viewModel.uiState.collectAsState()
                val settings by viewModel.settings.collectAsState()

                val historyEntries by viewModel.historyEntries.collectAsState()

                NavHost(navController = navController, startDestination = "main") {
                    composable("main") {
                        MainScreen(
                            uiState = uiState,
                            onInitialize = { viewModel.initializeEngines() },
                            onStartService = {
                                viewModel.startService()
                                bindToService()
                            },
                            onStopService = {
                                unbindFromService()
                                viewModel.stopService()
                            },
                            onStopSpeaking = { viewModel.stopSpeaking() },
                            onOpenSettings = { navController.navigate("settings") },
                            onOpenHistory = { navController.navigate("history") },
                            onOpenModels = { navController.navigate("models") }
                        )
                    }
                    composable("settings") {
                        SettingsScreen(
                            settings = settings,
                            onSpeechRateChanged = viewModel::updateSpeechRate,
                            onSpeechPitchChanged = viewModel::updateSpeechPitch,
                            onSsmlEnabledChanged = viewModel::updateSsmlEnabled,
                            onTtsBackendChanged = viewModel::updateTtsBackend,
                            onAutoScrollChanged = viewModel::updateAutoScroll,
                            onMaxScrollAttemptsChanged = viewModel::updateMaxScrollAttempts,
                            onPreferredBackendChanged = viewModel::updatePreferredBackend,
                            onAddTriggerPhrase = viewModel::addTriggerPhrase,
                            onRemoveTriggerPhrase = viewModel::removeTriggerPhrase,
                            onResetDefaults = viewModel::resetSettings,
                            onBack = { navController.popBackStack() }
                        )
                    }
                    composable("history") {
                        HistoryScreen(
                            entries = historyEntries,
                            onEntryClick = { entry ->
                                navController.navigate("history/${entry.id}")
                            },
                            onDeleteEntry = viewModel::deleteHistoryEntry,
                            onClearAll = viewModel::clearAllHistory,
                            onBack = { navController.popBackStack() }
                        )
                    }
                    composable("models") {
                        val downloadState by viewModel.downloadState.collectAsState()
                        val installedModels = remember { viewModel.getInstalledModels() }

                        ModelDownloadScreen(
                            availableModels = ModelDownloadManager.AVAILABLE_MODELS,
                            installedModels = installedModels,
                            downloadState = downloadState,
                            onDownload = viewModel::downloadModel,
                            onDelete = viewModel::deleteModel,
                            onBack = { navController.popBackStack() }
                        )
                    }
                    composable("history/{entryId}") { backStackEntry ->
                        val entryId = backStackEntry.arguments?.getString("entryId")?.toLongOrNull()
                        val entry = historyEntries.find { it.id == entryId }
                        if (entry != null) {
                            HistoryDetailScreen(
                                entry = entry,
                                onBack = { navController.popBackStack() }
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (VoiceAgentService.isRunning.value) {
            bindToService()
        }
    }

    override fun onStop() {
        super.onStop()
        unbindFromService()
    }

    private fun bindToService() {
        Intent(this, VoiceAgentService::class.java).also { intent ->
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    private fun unbindFromService() {
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
    }

    private fun requestPermissions() {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val needed = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }
}
