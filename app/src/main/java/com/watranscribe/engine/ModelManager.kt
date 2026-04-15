package com.watranscribe.engine

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ModelManager"
private const val HF_BASE = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main"

data class ModelInfo(
    val id: String,
    val displayName: String,
    val filename: String,
    val sizeMb: Int,
    val description: String
)

@Singleton
class ModelManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        val AVAILABLE_MODELS = listOf(
            ModelInfo("tiny.en-q5", "Tiny Q5", "ggml-tiny.en-q5_1.bin", 32,
                "39M params, quantized. Fastest possible, good for quick previews. English only. Expect some errors on mumbled or noisy audio."),
            ModelInfo("tiny.en", "Tiny", "ggml-tiny.en.bin", 78,
                "39M params. Very fast, rough accuracy. English only. Fine for clear speech, struggles with accents and background noise."),
            ModelInfo("base.en-q5", "Base Q5", "ggml-base.en-q5_1.bin", 60,
                "74M params, quantized. Fast and compact. English only. Noticeably better than Tiny with minimal speed cost."),
            ModelInfo("base.en", "Base", "ggml-base.en.bin", 148,
                "74M params. Solid all-rounder for English. Good accuracy on clear voice notes. Recommended starting point."),
            ModelInfo("small.en-q5", "Small Q5", "ggml-small.en-q5_1.bin", 190,
                "244M params, quantized. Best quality-to-size ratio. English only. Handles accents, fast speech, and some noise well."),
            ModelInfo("small.en", "Small", "ggml-small.en.bin", 488,
                "244M params. High accuracy for English. Reliable on most voice notes. Slower on older phones."),
            ModelInfo("medium.en-q5", "Medium Q5", "ggml-medium.en-q5_0.bin", 539,
                "769M params, quantized. Very high accuracy. English only. Near-perfect on clear audio, good with accents and noise."),
            ModelInfo("turbo-q5", "Turbo Q5", "ggml-large-v3-turbo-q5_0.bin", 574,
                "809M params, quantized. OpenAI's latest — large-v3 encoder with fast 4-layer decoder. Supports all languages. Best quality per compute."),
            ModelInfo("turbo-q8", "Turbo Q8", "ggml-large-v3-turbo-q8_0.bin", 874,
                "809M params, near-lossless quantization. Highest quality available. Multilingual. Use this if you want the absolute best and have storage."),
        )
    }

    private val modelsDir = File(context.filesDir, "models")

    private val _downloadProgress = MutableStateFlow<Pair<String, Float>?>(null) // modelId to 0.0-1.0
    val downloadProgress: StateFlow<Pair<String, Float>?> = _downloadProgress.asStateFlow()

    init {
        modelsDir.mkdirs()
    }

    fun isDownloaded(model: ModelInfo): Boolean {
        val file = File(modelsDir, model.filename)
        return file.exists() && file.length() > 1_000_000 // sanity check: at least 1MB
    }

    fun getDownloadedModels(): List<ModelInfo> {
        return AVAILABLE_MODELS.filter { isDownloaded(it) }
    }

    fun getModelPath(model: ModelInfo): String? {
        val file = File(modelsDir, model.filename)
        return if (file.exists()) file.absolutePath else null
    }

    fun getModelPathByFilename(filename: String): String? {
        val file = File(modelsDir, filename)
        return if (file.exists()) file.absolutePath else null
    }

    fun deleteModel(model: ModelInfo) {
        File(modelsDir, model.filename).delete()
        Log.d(TAG, "Deleted model: ${model.filename}")
    }

    fun getStorageUsed(): Long {
        return modelsDir.listFiles()?.sumOf { it.length() } ?: 0
    }

    suspend fun downloadModel(model: ModelInfo): Result<File> = withContext(Dispatchers.IO) {
        val url = "$HF_BASE/${model.filename}"
        val targetFile = File(modelsDir, model.filename)
        val tempFile = File(modelsDir, "${model.filename}.tmp")

        Log.d(TAG, "Downloading ${model.filename} from $url")
        _downloadProgress.value = model.id to 0f

        runCatching {
            val connection = URL(url).openConnection().apply {
                connectTimeout = 15_000
                readTimeout = 30_000
            }
            val totalSize = connection.contentLengthLong.takeIf { it > 0 }
                ?: (model.sizeMb.toLong() * 1024 * 1024) // fallback estimate

            connection.getInputStream().buffered(8192).use { input ->
                tempFile.outputStream().buffered(8192).use { output ->
                    var downloaded = 0L
                    val buffer = ByteArray(8192)
                    var lastUpdate = 0L

                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        downloaded += read

                        val now = System.currentTimeMillis()
                        if (now - lastUpdate > 200) { // throttle UI updates
                            _downloadProgress.value = model.id to (downloaded.toFloat() / totalSize)
                            lastUpdate = now
                        }
                    }
                }
            }

            tempFile.renameTo(targetFile)
            Log.d(TAG, "Download complete: ${targetFile.absolutePath} (${targetFile.length()} bytes)")
            _downloadProgress.value = null
            targetFile
        }.onFailure {
            tempFile.delete()
            _downloadProgress.value = null
            Log.e(TAG, "Download failed for ${model.filename}", it)
        }
    }
}
