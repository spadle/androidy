package com.androidy.voicereader.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private val SPEECH_RATE = floatPreferencesKey("speech_rate")
        private val SPEECH_PITCH = floatPreferencesKey("speech_pitch")
        private val TRIGGER_PHRASES = stringSetPreferencesKey("trigger_phrases")
        private val PREFERRED_BACKEND = stringPreferencesKey("preferred_backend")
        private val AUTO_SCROLL = booleanPreferencesKey("auto_scroll")
        private val MAX_SCROLL_ATTEMPTS = intPreferencesKey("max_scroll_attempts")
        private val SSML_ENABLED = booleanPreferencesKey("ssml_enabled")
        private val TTS_BACKEND = stringPreferencesKey("tts_backend")

        val DEFAULT_TRIGGER_PHRASES = setOf(
            "read this",
            "read it",
            "read the post",
            "read for me",
            "what does it say",
            "summarize this",
            "summarize it",
            "tell me what it says",
            "read the screen",
            "hey reader"
        )
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            speechRate = prefs[SPEECH_RATE] ?: 1.0f,
            speechPitch = prefs[SPEECH_PITCH] ?: 1.0f,
            triggerPhrases = prefs[TRIGGER_PHRASES] ?: DEFAULT_TRIGGER_PHRASES,
            preferredBackend = prefs[PREFERRED_BACKEND] ?: "auto",
            autoScroll = prefs[AUTO_SCROLL] ?: true,
            maxScrollAttempts = prefs[MAX_SCROLL_ATTEMPTS] ?: 15,
            ssmlEnabled = prefs[SSML_ENABLED] ?: true,
            ttsBackend = prefs[TTS_BACKEND] ?: "android"
        )
    }

    suspend fun updateSpeechRate(rate: Float) {
        context.dataStore.edit { it[SPEECH_RATE] = rate }
    }

    suspend fun updateSpeechPitch(pitch: Float) {
        context.dataStore.edit { it[SPEECH_PITCH] = pitch }
    }

    suspend fun updateTriggerPhrases(phrases: Set<String>) {
        context.dataStore.edit { it[TRIGGER_PHRASES] = phrases }
    }

    suspend fun updatePreferredBackend(backend: String) {
        context.dataStore.edit { it[PREFERRED_BACKEND] = backend }
    }

    suspend fun updateAutoScroll(enabled: Boolean) {
        context.dataStore.edit { it[AUTO_SCROLL] = enabled }
    }

    suspend fun updateMaxScrollAttempts(attempts: Int) {
        context.dataStore.edit { it[MAX_SCROLL_ATTEMPTS] = attempts }
    }

    suspend fun updateSsmlEnabled(enabled: Boolean) {
        context.dataStore.edit { it[SSML_ENABLED] = enabled }
    }

    suspend fun updateTtsBackend(backend: String) {
        context.dataStore.edit { it[TTS_BACKEND] = backend }
    }

    suspend fun resetToDefaults() {
        context.dataStore.edit { it.clear() }
    }
}

data class AppSettings(
    val speechRate: Float = 1.0f,
    val speechPitch: Float = 1.0f,
    val triggerPhrases: Set<String> = SettingsRepository.DEFAULT_TRIGGER_PHRASES,
    val preferredBackend: String = "auto",
    val autoScroll: Boolean = true,
    val maxScrollAttempts: Int = 15,
    val ssmlEnabled: Boolean = true,
    val ttsBackend: String = "android" // "android" or "ondevice"
)
