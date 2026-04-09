package com.androidy.voicereader.ui

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun MainScreen(
    uiState: UiState,
    onInitialize: () -> Unit,
    onStartService: () -> Unit,
    onStopService: () -> Unit,
    onStopSpeaking: () -> Unit,
    onOpenSettings: () -> Unit = {},
    onOpenHistory: () -> Unit = {},
    onOpenModels: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header with settings button
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Voice Reader",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "AI-powered screen reading assistant",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row {
                IconButton(onClick = onOpenHistory) {
                    Icon(
                        imageVector = Icons.Default.History,
                        contentDescription = "History",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(28.dp)
                    )
                }
                IconButton(onClick = onOpenSettings) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Settings",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Status indicator
        StatusPulse(isActive = uiState.isServiceRunning)

        Spacer(modifier = Modifier.height(8.dp))

        // Setup checklist
        SetupCard(
            title = "Setup Status",
            items = listOf(
                SetupItem(
                    label = "Accessibility Service",
                    isReady = uiState.isAccessibilityEnabled,
                    icon = Icons.Default.Accessibility
                ),
                SetupItem(
                    label = "Gemma 4 AI Model",
                    isReady = uiState.isModelLoaded,
                    icon = Icons.Default.Psychology,
                    detail = if (uiState.isModelLoaded)
                        "${uiState.activeModel} via ${uiState.activeBackend}"
                    else
                        uiState.modelStatus
                ),
                SetupItem(
                    label = "Background Service",
                    isReady = uiState.isServiceRunning,
                    icon = Icons.Default.MicExternalOn
                )
            )
        )

        // Action buttons
        if (!uiState.isAccessibilityEnabled) {
            OutlinedButton(
                onClick = { openAccessibilitySettings(context) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.Settings, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Enable Accessibility Service")
            }
        }

        if (!uiState.isModelLoaded) {
            Button(
                onClick = onInitialize,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.Download, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Initialize AI Engine")
            }
            OutlinedButton(
                onClick = onOpenModels,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.CloudDownload, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Download AI Model")
            }
        }

        // Main control button
        if (uiState.isAccessibilityEnabled && uiState.isModelLoaded) {
            Spacer(modifier = Modifier.height(8.dp))

            if (!uiState.isServiceRunning) {
                Button(
                    onClick = onStartService,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(28.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Start Voice Agent", fontSize = 18.sp)
                }
            } else {
                Button(
                    onClick = onStopService,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(28.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Stop Voice Agent", fontSize = 18.sp)
                }

                OutlinedButton(
                    onClick = onStopSpeaking,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.VolumeOff, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Stop Speaking")
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Instructions card
        InstructionsCard(isRunning = uiState.isServiceRunning)

        // Model info
        ModelInfoCard()
    }
}

@Composable
fun StatusPulse(isActive: Boolean) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isActive) 1.2f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )
    val color by animateColorAsState(
        targetValue = if (isActive) Color(0xFF4CAF50) else Color(0xFF9E9E9E),
        label = "color"
    )

    Box(
        modifier = Modifier
            .size(80.dp)
            .scale(if (isActive) scale else 1f)
            .clip(CircleShape)
            .background(color.copy(alpha = 0.2f)),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(50.dp)
                .clip(CircleShape)
                .background(color),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (isActive) Icons.Default.Mic else Icons.Default.MicOff,
                contentDescription = if (isActive) "Listening" else "Not active",
                tint = Color.White,
                modifier = Modifier.size(28.dp)
            )
        }
    }

    Text(
        text = if (isActive) "Listening for commands..." else "Not active",
        style = MaterialTheme.typography.bodyLarge,
        color = if (isActive) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurfaceVariant
    )
}

data class SetupItem(
    val label: String,
    val isReady: Boolean,
    val icon: ImageVector,
    val detail: String? = null
)

@Composable
fun SetupCard(title: String, items: List<SetupItem>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(12.dp))

            items.forEach { item ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = item.icon,
                        contentDescription = null,
                        tint = if (item.isReady) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = item.label,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        if (item.detail != null) {
                            Text(
                                text = item.detail,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Icon(
                        imageVector = if (item.isReady) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                        contentDescription = if (item.isReady) "Ready" else "Not ready",
                        tint = if (item.isReady) Color(0xFF4CAF50) else Color(0xFFBDBDBD),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun InstructionsCard(isRunning: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "How to use",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(8.dp))

            if (isRunning) {
                InstructionStep("1", "Open any app with text (Facebook, Twitter, etc.)")
                InstructionStep("2", "Say \"Read this\" or \"Summarize this\"")
                InstructionStep("3", "The AI will extract, analyze, and read the content")
                InstructionStep("4", "Important parts are emphasized, filler is skipped")
            } else {
                InstructionStep("1", "Enable the Accessibility Service in settings")
                InstructionStep("2", "Initialize the AI engine (downloads Gemma model)")
                InstructionStep("3", "Start the Voice Agent")
            }
        }
    }
}

@Composable
fun InstructionStep(number: String, text: String) {
    Row(
        modifier = Modifier.padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = number,
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
fun ModelInfoCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Model Setup",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "This app uses Google's Gemma 4 E2B model running entirely on-device. " +
                        "It uses LiteRT-LM (NPU/GPU accelerated) when available, with MediaPipe as fallback.\n\n" +
                        "Download a model file and place it in the app's files directory:\n" +
                        "\u2022 gemma-4-e2b-it.litertlm (~1.3GB, recommended)\n" +
                        "\u2022 gemma-4-e2b-it.task (~1.3GB, MediaPipe format)\n" +
                        "\u2022 gemma-4-e4b-it.litertlm (~2.5GB, more capable)\n\n" +
                        "Available from HuggingFace (litert-community) or Kaggle (google/gemma-4).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Start
            )
        }
    }
}

private fun openAccessibilitySettings(context: Context) {
    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK
    })
}
