package com.androidy.voicereader.data

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ModelDownloadManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "ModelDownloadManager"

        val AVAILABLE_MODELS = listOf(
            ModelInfo(
                id = "gemma3-1b-int4-task",
                name = "Gemma 3 1B (Recommended)",
                description = "1B params, ~555MB — int4 quantized, works on all devices",
                fileName = "gemma3-1b-it-int4.task",
                url = "https://huggingface.co/litert-community/Gemma3-1B-IT/resolve/main/gemma3-1b-it-int4.task",
                sizeBytes = 555_000_000L
            ),
            ModelInfo(
                id = "gemma3-1b-int8-task",
                name = "Gemma 3 1B (Higher Quality)",
                description = "1B params, ~1GB — int8 quantized, better quality",
                fileName = "Gemma3-1B-IT_multi-prefill-seq_q8_ekv2048.task",
                url = "https://huggingface.co/litert-community/Gemma3-1B-IT/resolve/main/Gemma3-1B-IT_multi-prefill-seq_q8_ekv2048.task",
                sizeBytes = 1_070_000_000L
            ),
            ModelInfo(
                id = "gemma3-1b-int4-litertlm",
                name = "Gemma 3 1B (LiteRT-LM)",
                description = "1B params, ~584MB — NPU/GPU accelerated, fastest",
                fileName = "gemma3-1b-it-int4.litertlm",
                url = "https://huggingface.co/litert-community/Gemma3-1B-IT/resolve/main/gemma3-1b-it-int4.litertlm",
                sizeBytes = 584_000_000L
            )
        )
    }

    private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val downloadState: StateFlow<DownloadState> = _downloadState

    private val _installedModels = MutableStateFlow<List<String>>(emptyList())
    val installedModels: StateFlow<List<String>> = _installedModels

    init {
        refreshInstalledModels()
    }

    fun getModelsDir(): File = context.filesDir

    fun refreshInstalledModels() {
        val dir = getModelsDir()
        _installedModels.value = dir.listFiles()
            ?.filter { it.extension in listOf("task", "litertlm", "tflite", "bin") }
            ?.map { it.name }
            ?: emptyList()
    }

    suspend fun downloadModel(model: ModelInfo) {
        val destFile = File(getModelsDir(), model.fileName)
        val tempFile = File(getModelsDir(), "${model.fileName}.tmp")

        if (destFile.exists()) {
            _downloadState.value = DownloadState.Completed(model.fileName)
            return
        }

        withContext(Dispatchers.IO) {
            try {
                _downloadState.value = DownloadState.Downloading(0f, model.fileName)
                Log.d(TAG, "Starting download: ${model.url}")

                val connection = URL(model.url).openConnection() as HttpURLConnection
                connection.connectTimeout = 30_000
                connection.readTimeout = 30_000
                connection.instanceFollowRedirects = true
                connection.connect()

                val totalBytes = connection.contentLengthLong.takeIf { it > 0 } ?: model.sizeBytes
                var downloadedBytes = 0L

                connection.inputStream.buffered().use { input ->
                    tempFile.outputStream().buffered().use { output ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int

                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            downloadedBytes += bytesRead

                            val progress = (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
                            _downloadState.value = DownloadState.Downloading(progress, model.fileName)
                        }
                    }
                }

                tempFile.renameTo(destFile)
                _downloadState.value = DownloadState.Completed(model.fileName)
                refreshInstalledModels()
                Log.d(TAG, "Download completed: ${model.fileName}")

            } catch (e: Exception) {
                Log.e(TAG, "Download failed", e)
                tempFile.delete()
                _downloadState.value = DownloadState.Error(e.message ?: "Download failed")
            }
        }
    }

    fun cancelDownload() {
        _downloadState.value = DownloadState.Idle
    }

    fun deleteModel(fileName: String) {
        File(getModelsDir(), fileName).delete()
        refreshInstalledModels()
    }
}

data class ModelInfo(
    val id: String,
    val name: String,
    val description: String,
    val fileName: String,
    val url: String,
    val sizeBytes: Long
)

sealed class DownloadState {
    data object Idle : DownloadState()
    data class Downloading(val progress: Float, val fileName: String) : DownloadState()
    data class Completed(val fileName: String) : DownloadState()
    data class Error(val message: String) : DownloadState()
}
