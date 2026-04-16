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
private const val HF_BASE_WHISPER = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main"
private const val MOONSHINE_BASE = "https://download.moonshine.ai/model"

const val ENGINE_WHISPER = "whisper"
const val ENGINE_MOONSHINE = "moonshine"

data class ModelInfo(
    val id: String,
    val displayName: String,
    /** For whisper: file name under models/. For moonshine: directory name under models/. */
    val filename: String,
    val sizeMb: Int,
    val description: String,
    val engine: String = ENGINE_WHISPER,
    /**
     * For Moonshine: the Transcriber architecture int expected by
     * `Transcriber.loadFromFiles(dir, archInt)`. Unused for whisper.
     */
    val moonshineArch: Int = 0,
    /**
     * For Moonshine: URL path under download.moonshine.ai/model/ for the files
     * (e.g. "tiny-en/quantized/tiny-en" or "medium-streaming-en/quantized").
     * Unused for whisper.
     */
    val moonshineRemotePath: String = "",
    /**
     * For Moonshine: the set of files to download. Only the files the Transcriber
     * actually consumes — download.moonshine.ai ships extras we skip.
     */
    val moonshineFiles: List<String> = emptyList(),
)

@Singleton
class ModelManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        // Non-streaming Moonshine variants (arch 0, 1) load 3 files.
        private val NON_STREAMING_FILES = listOf(
            "encoder_model.ort",
            "decoder_model_merged.ort",
            "tokenizer.bin",
        )

        // Streaming variants (arch 2-5) need the full set of components.
        private val STREAMING_FILES = listOf(
            "encoder.ort",
            "adapter.ort",
            "cross_kv.ort",
            "decoder_kv.ort",
            "frontend.ort",
            "streaming_config.json",
            "tokenizer.bin",
        )

        val AVAILABLE_MODELS = listOf(
            // --- whisper.cpp models (HuggingFace ggerganov/whisper.cpp) ---
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

            // --- Moonshine models via official Moonshine Android SDK ---
            ModelInfo(
                id = "moonshine-tiny-en",
                displayName = "Moonshine Tiny (EN)",
                filename = "moonshine-tiny-en",
                sizeMb = 42,
                description = "27M params. Fastest Moonshine. ~12.7% WER. Good for quick previews.",
                engine = ENGINE_MOONSHINE,
                moonshineArch = 0,
                moonshineRemotePath = "tiny-en/quantized/tiny-en",
                moonshineFiles = NON_STREAMING_FILES,
            ),
            ModelInfo(
                id = "moonshine-base-en",
                displayName = "Moonshine Base (EN)",
                filename = "moonshine-base-en",
                sizeMb = 120,
                description = "58M params. ~10% WER. Good all-rounder.",
                engine = ENGINE_MOONSHINE,
                moonshineArch = 1,
                moonshineRemotePath = "base-en/quantized/base-en",
                moonshineFiles = NON_STREAMING_FILES,
            ),
            ModelInfo(
                id = "moonshine-medium-streaming-en",
                displayName = "Moonshine Medium Streaming (EN)",
                filename = "moonshine-medium-streaming-en",
                sizeMb = 245,
                description = "245M params streaming model. ~6.65% WER — best quality Moonshine. Streaming architecture.",
                engine = ENGINE_MOONSHINE,
                moonshineArch = 5,
                moonshineRemotePath = "medium-streaming-en/quantized",
                moonshineFiles = STREAMING_FILES,
            ),
        )
    }

    private val modelsDir = File(context.filesDir, "models")

    private val _downloadProgress = MutableStateFlow<Pair<String, Float>?>(null) // modelId to 0.0-1.0
    val downloadProgress: StateFlow<Pair<String, Float>?> = _downloadProgress.asStateFlow()

    init {
        modelsDir.mkdirs()
    }

    fun isDownloaded(model: ModelInfo): Boolean {
        return when (model.engine) {
            ENGINE_MOONSHINE -> {
                val dir = File(modelsDir, model.filename)
                dir.isDirectory && model.moonshineFiles.all { File(dir, it).exists() }
            }
            else -> {
                val file = File(modelsDir, model.filename)
                file.exists() && file.length() > 1_000_000
            }
        }
    }

    fun getDownloadedModels(): List<ModelInfo> {
        return AVAILABLE_MODELS.filter { isDownloaded(it) }
    }

    fun getModelPath(model: ModelInfo): String? {
        val target = File(modelsDir, model.filename)
        return if (target.exists()) target.absolutePath else null
    }

    fun getModelPathByFilename(filename: String): String? {
        val file = File(modelsDir, filename)
        return if (file.exists()) file.absolutePath else null
    }

    fun deleteModel(model: ModelInfo) {
        val target = File(modelsDir, model.filename)
        if (target.isDirectory) {
            target.deleteRecursively()
        } else {
            target.delete()
        }
        Log.d(TAG, "Deleted model: ${model.filename}")
    }

    fun getStorageUsed(): Long {
        return modelsDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    suspend fun downloadModel(model: ModelInfo): Result<File> = withContext(Dispatchers.IO) {
        when (model.engine) {
            ENGINE_MOONSHINE -> downloadMoonshineModel(model)
            else -> downloadWhisperModel(model)
        }
    }

    private suspend fun downloadWhisperModel(model: ModelInfo): Result<File> = withContext(Dispatchers.IO) {
        val url = "$HF_BASE_WHISPER/${model.filename}"
        val targetFile = File(modelsDir, model.filename)
        val tempFile = File(modelsDir, "${model.filename}.tmp")

        Log.d(TAG, "Downloading whisper ${model.filename} from $url")
        _downloadProgress.value = model.id to 0f

        runCatching {
            streamToFile(url, tempFile, model.id, fallbackSize = model.sizeMb.toLong() * 1024 * 1024)
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

    private suspend fun downloadMoonshineModel(model: ModelInfo): Result<File> = withContext(Dispatchers.IO) {
        val targetDir = File(modelsDir, model.filename)
        targetDir.mkdirs()

        Log.d(TAG, "Downloading moonshine ${model.id} (${model.moonshineFiles.size} files) from $MOONSHINE_BASE/${model.moonshineRemotePath}")
        _downloadProgress.value = model.id to 0f

        runCatching {
            val totalFiles = model.moonshineFiles.size
            model.moonshineFiles.forEachIndexed { index, fileName ->
                val url = "$MOONSHINE_BASE/${model.moonshineRemotePath}/$fileName"
                val dest = File(targetDir, fileName)
                val tmp = File(targetDir, "$fileName.tmp")
                Log.d(TAG, "  [${index + 1}/$totalFiles] $fileName <- $url")
                // Reserve progress slot per file: even ÷ among files, using fileFraction for the bar.
                val base = index.toFloat() / totalFiles
                val span = 1f / totalFiles
                streamToFile(
                    url = url,
                    tempFile = tmp,
                    modelId = model.id,
                    fallbackSize = 1024L * 1024, // unknown per-file; fall back to 1MB
                    progressBase = base,
                    progressSpan = span,
                )
                tmp.renameTo(dest)
            }

            // Verify all required files are present
            val missing = model.moonshineFiles.filterNot { File(targetDir, it).exists() }
            if (missing.isNotEmpty()) {
                error("Moonshine download incomplete. Missing: ${missing.joinToString()}")
            }

            Log.d(TAG, "Moonshine model ready: ${targetDir.absolutePath}")
            _downloadProgress.value = null
            targetDir
        }.onFailure {
            targetDir.deleteRecursively()
            _downloadProgress.value = null
            Log.e(TAG, "Moonshine download failed for ${model.id}", it)
        }
    }

    /**
     * Streams [url] into [tempFile], publishing progress scaled into the band
     * `[progressBase, progressBase + progressSpan]` (for multi-file downloads).
     * Follows HTTP redirects manually.
     */
    private fun streamToFile(
        url: String,
        tempFile: File,
        modelId: String,
        fallbackSize: Long,
        progressBase: Float = 0f,
        progressSpan: Float = 1f,
    ) {
        var current = url
        var redirects = 0
        while (true) {
            val conn = (URL(current).openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 60_000
                instanceFollowRedirects = false
            }
            val code = conn.responseCode
            if (code in 300..399) {
                val loc = conn.getHeaderField("Location")
                    ?: error("Redirect without Location header at $current")
                current = if (loc.startsWith("http")) loc else URL(URL(current), loc).toString()
                conn.disconnect()
                if (++redirects > 5) error("Too many redirects fetching $url")
                continue
            }
            if (code !in 200..299) {
                conn.disconnect()
                error("HTTP $code fetching $current")
            }
            val totalSize = conn.contentLengthLong.takeIf { it > 0 } ?: fallbackSize
            conn.inputStream.buffered(16 * 1024).use { input ->
                tempFile.outputStream().buffered(16 * 1024).use { output ->
                    var downloaded = 0L
                    val buffer = ByteArray(16 * 1024)
                    var lastUpdate = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        val now = System.currentTimeMillis()
                        if (now - lastUpdate > 200) {
                            val frac = progressBase + (downloaded.toFloat() / totalSize).coerceAtMost(1f) * progressSpan
                            _downloadProgress.value = modelId to frac
                            lastUpdate = now
                        }
                    }
                }
            }
            conn.disconnect()
            return
        }
    }
}
