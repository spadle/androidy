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
                id = "gemma-4-e2b-task",
                name = "Gemma 4 E2B (MediaPipe)",
                description = "2.5B params, ~1.3GB — MediaPipe format, widest compatibility",
                fileName = "gemma-4-e2b-it.task",
                url = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-e2b-it.task",
                sizeBytes = 1_400_000_000L
            ),
            ModelInfo(
                id = "gemma-4-e2b-litertlm",
                name = "Gemma 4 E2B (LiteRT-LM)",
                description = "2.5B params, ~1.3GB — NPU/GPU accelerated, fastest",
                fileName = "gemma-4-e2b-it.litertlm",
                url = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-e2b-it.litertlm",
                sizeBytes = 1_300_000_000L
            )
        )
    }

    private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val downloadState: StateFlow<DownloadState> = _downloadState

    fun getModelsDir(): File = context.filesDir

    fun getInstalledModels(): List<String> {
        val dir = getModelsDir()
        return dir.listFiles()
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
