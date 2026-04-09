package com.androidy.voicereader.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.androidy.voicereader.accessibility.ScreenReaderAccessibilityService
import com.androidy.voicereader.data.AppSettings
import com.androidy.voicereader.data.DownloadState
import com.androidy.voicereader.data.ModelDownloadManager
import com.androidy.voicereader.data.ModelInfo
import com.androidy.voicereader.data.ReadingHistoryDao
import com.androidy.voicereader.data.ReadingHistoryEntry
import com.androidy.voicereader.data.SettingsRepository
import com.androidy.voicereader.llm.GemmaLlmEngine
import com.androidy.voicereader.service.VoiceAgentService
import com.androidy.voicereader.tts.IntelligentTtsEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    application: Application,
    val llmEngine: GemmaLlmEngine,
    val ttsEngine: IntelligentTtsEngine,
    val settingsRepository: SettingsRepository,
    val historyDao: ReadingHistoryDao,
    val modelDownloadManager: ModelDownloadManager
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState

    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())

    val historyEntries: StateFlow<List<ReadingHistoryEntry>> = historyDao.getRecent(50)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch {
            combine(
                ScreenReaderAccessibilityService.isConnected,
                VoiceAgentService.isRunning,
                llmEngine.isModelLoaded,
                llmEngine.loadingProgress,
                llmEngine.activeBackend,
                llmEngine.activeModel
            ) { values ->
                _uiState.value.copy(
                    isAccessibilityEnabled = values[0] as Boolean,
                    isServiceRunning = values[1] as Boolean,
                    isModelLoaded = values[2] as Boolean,
                    modelStatus = values[3] as String,
                    activeBackend = values[4] as String,
                    activeModel = values[5] as String
                )
            }.collect { state ->
                _uiState.value = state
            }
        }
    }

    fun initializeEngines() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(modelStatus = "Initializing TTS...")
            ttsEngine.initialize()

            _uiState.value = _uiState.value.copy(modelStatus = "Loading Gemma 4 model...")
            llmEngine.initialize()
        }
    }

    fun startService() {
        VoiceAgentService.start(getApplication())
    }

    fun stopService() {
        ttsEngine.stop()
        VoiceAgentService.stop(getApplication())
    }

    fun stopSpeaking() {
        ttsEngine.stop()
    }

    // Settings actions
    fun updateSpeechRate(rate: Float) {
        viewModelScope.launch { settingsRepository.updateSpeechRate(rate) }
    }

    fun updateSpeechPitch(pitch: Float) {
        viewModelScope.launch { settingsRepository.updateSpeechPitch(pitch) }
    }

    fun updateSsmlEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.updateSsmlEnabled(enabled) }
    }

    fun updateTtsBackend(backend: String) {
        viewModelScope.launch { settingsRepository.updateTtsBackend(backend) }
    }

    fun updateAutoScroll(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.updateAutoScroll(enabled) }
    }

    fun updateMaxScrollAttempts(attempts: Int) {
        viewModelScope.launch { settingsRepository.updateMaxScrollAttempts(attempts) }
    }

    fun updatePreferredBackend(backend: String) {
        viewModelScope.launch { settingsRepository.updatePreferredBackend(backend) }
    }

    fun addTriggerPhrase(phrase: String) {
        viewModelScope.launch {
            val current = settings.value.triggerPhrases.toMutableSet()
            current.add(phrase)
            settingsRepository.updateTriggerPhrases(current)
        }
    }

    fun removeTriggerPhrase(phrase: String) {
        viewModelScope.launch {
            val current = settings.value.triggerPhrases.toMutableSet()
            current.remove(phrase)
            settingsRepository.updateTriggerPhrases(current)
        }
    }

    fun resetSettings() {
        viewModelScope.launch { settingsRepository.resetToDefaults() }
    }

    // History actions
    fun deleteHistoryEntry(entry: ReadingHistoryEntry) {
        viewModelScope.launch { historyDao.delete(entry) }
    }

    fun clearAllHistory() {
        viewModelScope.launch { historyDao.deleteAll() }
    }

    // Model download actions
    val downloadState: StateFlow<DownloadState> = modelDownloadManager.downloadState

    val installedModels: StateFlow<List<String>> = modelDownloadManager.installedModels

    fun downloadModel(model: ModelInfo) {
        viewModelScope.launch { modelDownloadManager.downloadModel(model) }
    }

    fun deleteModel(fileName: String) {
        modelDownloadManager.deleteModel(fileName)
    }

    override fun onCleared() {
        super.onCleared()
        llmEngine.release()
        ttsEngine.release()
    }
}

data class UiState(
    val isAccessibilityEnabled: Boolean = false,
    val isServiceRunning: Boolean = false,
    val isModelLoaded: Boolean = false,
    val modelStatus: String = "Not initialized",
    val agentStatus: String = "Idle",
    val activeBackend: String = "None",
    val activeModel: String = "None"
)
