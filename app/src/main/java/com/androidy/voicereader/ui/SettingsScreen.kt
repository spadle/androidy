package com.androidy.voicereader.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.androidy.voicereader.data.AppSettings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    onSpeechRateChanged: (Float) -> Unit,
    onSpeechPitchChanged: (Float) -> Unit,
    onSsmlEnabledChanged: (Boolean) -> Unit,
    onTtsBackendChanged: (String) -> Unit,
    onAutoScrollChanged: (Boolean) -> Unit,
    onMaxScrollAttemptsChanged: (Int) -> Unit,
    onPreferredBackendChanged: (String) -> Unit,
    onAddTriggerPhrase: (String) -> Unit,
    onRemoveTriggerPhrase: (String) -> Unit,
    onResetDefaults: () -> Unit,
    onBack: () -> Unit
) {
    val scrollState = rememberScrollState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onResetDefaults) {
                        Icon(Icons.Default.RestartAlt, contentDescription = "Reset to defaults")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Voice Settings
            SettingsSectionHeader(icon = Icons.Default.RecordVoiceOver, title = "Voice")

            SliderSetting(
                label = "Speech Rate",
                value = settings.speechRate,
                valueRange = 0.5f..2.0f,
                valueLabel = "%.1fx".format(settings.speechRate),
                onValueChange = onSpeechRateChanged
            )

            SliderSetting(
                label = "Speech Pitch",
                value = settings.speechPitch,
                valueRange = 0.5f..2.0f,
                valueLabel = "%.1fx".format(settings.speechPitch),
                onValueChange = onSpeechPitchChanged
            )

            SwitchSetting(
                label = "SSML Rendering",
                description = "Use rich prosody markup for expressive speech",
                checked = settings.ssmlEnabled,
                onCheckedChange = onSsmlEnabledChanged
            )

            TtsBackendSelector(
                selected = settings.ttsBackend,
                onSelected = onTtsBackendChanged
            )

            HorizontalDivider()

            // Reading Settings
            SettingsSectionHeader(icon = Icons.Default.AutoStories, title = "Reading")

            SwitchSetting(
                label = "Auto-Scroll",
                description = "Scroll to capture off-screen content",
                checked = settings.autoScroll,
                onCheckedChange = onAutoScrollChanged
            )

            if (settings.autoScroll) {
                SliderSetting(
                    label = "Max Scroll Attempts",
                    value = settings.maxScrollAttempts.toFloat(),
                    valueRange = 1f..30f,
                    steps = 29,
                    valueLabel = "${settings.maxScrollAttempts}",
                    onValueChange = { onMaxScrollAttemptsChanged(it.toInt()) }
                )
            }

            HorizontalDivider()

            // Model Settings
            SettingsSectionHeader(icon = Icons.Default.Psychology, title = "AI Model")

            BackendSelector(
                selected = settings.preferredBackend,
                onSelected = onPreferredBackendChanged
            )

            HorizontalDivider()

            // Trigger Phrases
            SettingsSectionHeader(icon = Icons.Default.Mic, title = "Trigger Phrases")

            TriggerPhrasesEditor(
                phrases = settings.triggerPhrases,
                onAdd = onAddTriggerPhrase,
                onRemove = onRemoveTriggerPhrase
            )

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SettingsSectionHeader(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 4.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun SliderSetting(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    valueLabel: String,
    onValueChange: (Float) -> Unit,
    steps: Int = 0
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = valueLabel,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps
        )
    }
}

@Composable
private fun SwitchSetting(
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun TtsBackendSelector(selected: String, onSelected: (String) -> Unit) {
    val options = listOf("android" to "Android System TTS", "ondevice" to "On-Device Model (TFLite)")

    Column(modifier = Modifier.fillMaxWidth()) {
        Text("TTS Engine", style = MaterialTheme.typography.bodyMedium)
        options.forEach { (value, label) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(selected = selected == value, onClick = { onSelected(value) })
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(text = label, style = MaterialTheme.typography.bodyMedium)
                    if (value == "ondevice") {
                        Text(
                            text = "Requires a TTS model file (tts_model.tflite)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BackendSelector(selected: String, onSelected: (String) -> Unit) {
    val options = listOf("auto" to "Auto (Best Available)", "litert" to "LiteRT-LM (NPU/GPU)", "mediapipe" to "MediaPipe (Fallback)")

    Column(modifier = Modifier.fillMaxWidth()) {
        options.forEach { (value, label) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = selected == value,
                    onClick = { onSelected(value) }
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = label, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun TriggerPhrasesEditor(
    phrases: Set<String>,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit
) {
    var newPhrase by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxWidth()) {
        // Add new phrase
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = newPhrase,
                onValueChange = { newPhrase = it },
                label = { Text("Add phrase") },
                modifier = Modifier.weight(1f),
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            FilledIconButton(
                onClick = {
                    if (newPhrase.isNotBlank()) {
                        onAdd(newPhrase.lowercase().trim())
                        newPhrase = ""
                    }
                },
                enabled = newPhrase.isNotBlank()
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add")
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Existing phrases as chips
        @OptIn(ExperimentalLayoutApi::class)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            phrases.sorted().forEach { phrase ->
                InputChip(
                    selected = false,
                    onClick = { },
                    label = { Text(phrase) },
                    trailingIcon = {
                        IconButton(
                            onClick = { onRemove(phrase) },
                            modifier = Modifier.size(18.dp)
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Remove",
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                )
            }
        }
    }
}
