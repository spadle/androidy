package com.androidy.voicereader.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.androidy.voicereader.accessibility.ScreenReaderAccessibilityService
import com.androidy.voicereader.llm.GemmaLlmEngine
import com.androidy.voicereader.service.VoiceAgentService
import com.androidy.voicereader.tts.IntelligentTtsEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    application: Application,
    val llmEngine: GemmaLlmEngine,
    val ttsEngine: IntelligentTtsEngine
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState

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
