package com.watranscribe.engine

import android.content.Context
import android.util.Log
import com.watranscribe.data.local.TimedSegment
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "WhisperEngine"

@Singleton
class WhisperEngine @Inject constructor(
    @ApplicationContext private val context: Context
) : TranscriptionEngine {
    override val id: String = "whisper"

    private var contextPtr: Long = 0L
    private val mutex = Mutex()
    private var currentModel: String? = null

    val preferredThreadCount: Int
        get() = maxOf(1, Runtime.getRuntime().availableProcessors() - 2)

    override suspend fun loadModel(modelFilename: String): Unit = withContext(Dispatchers.IO) {
        val modelName = modelFilename
        mutex.withLock {
            if (contextPtr != 0L && currentModel == modelName) return@withLock
            if (contextPtr != 0L) {
                WhisperJni.freeContext(contextPtr)
                contextPtr = 0L
            }
            val modelFile = File(context.filesDir, "models/$modelName")
            if (!modelFile.exists()) {
                error("Model not found: $modelName. Download it from Settings.")
            }
            Log.d(TAG, "Loading model: ${modelFile.absolutePath} (${modelFile.length() / 1024 / 1024}MB)")
            contextPtr = WhisperJni.initContext(modelFile.absolutePath)
            if (contextPtr == 0L) {
                currentModel = null
                error("Failed to initialize whisper context from $modelName")
            }
            currentModel = modelName
            Log.d(TAG, "Model loaded successfully: $modelName")
        }
    }

    override fun getCurrentModelName(): String? = currentModel

    /**
     * Transcribe audio samples, streaming segments via the returned Flow.
     * The Flow emits partial transcript strings as each segment completes.
     * The final complete text is returned by the suspend function.
     */
    suspend fun transcribeStreaming(samples: FloatArray, segmentFlow: MutableSharedFlow<String>): String =
        withContext(Dispatchers.Default) {
            mutex.withLock {
                check(contextPtr != 0L) { "Whisper model not loaded. Call loadModel() first." }

                val callback = object : WhisperJni.SegmentCallback {
                    override fun onSegment(text: String) {
                        segmentFlow.tryEmit(text)
                    }
                }

                WhisperJni.fullTranscribe(contextPtr, samples, preferredThreadCount, callback)
            }
        }

    /** Transcribe and return text + timed segments. */
    override suspend fun transcribeWithTimings(samples: FloatArray, segmentFlow: MutableSharedFlow<String>?): TranscribeResult =
        withContext(Dispatchers.Default) {
            mutex.withLock {
                check(contextPtr != 0L) { "Whisper model not loaded. Call loadModel() first." }

                // Emit the full running transcript after each segment (callers expect
                // the latest-state contract, not per-segment deltas).
                val callback = segmentFlow?.let { flow ->
                    val buf = StringBuilder()
                    object : WhisperJni.SegmentCallback {
                        override fun onSegment(text: String) {
                            buf.append(text)
                            flow.tryEmit(buf.toString())
                        }
                    }
                }

                val text = WhisperJni.fullTranscribe(contextPtr, samples, preferredThreadCount, callback)
                val segmentsRaw = WhisperJni.getSegments(contextPtr)
                val segments = parseSegments(segmentsRaw)
                TranscribeResult(text, segments)
            }
        }

    private fun parseSegments(raw: String): List<TimedSegment> {
        return raw.lines().filter { it.isNotBlank() }.mapNotNull { line ->
            val parts = line.split("|", limit = 3)
            if (parts.size == 3) {
                TimedSegment(
                    text = parts[2],
                    startMs = parts[0].toLongOrNull() ?: 0,
                    endMs = parts[1].toLongOrNull() ?: 0
                )
            } else null
        }
    }

    /** Simple non-streaming transcription. */
    suspend fun transcribe(samples: FloatArray): String = withContext(Dispatchers.Default) {
        mutex.withLock {
            check(contextPtr != 0L) { "Whisper model not loaded. Call loadModel() first." }
            WhisperJni.fullTranscribe(contextPtr, samples, preferredThreadCount, null)
        }
    }

    override suspend fun release() {
        mutex.withLock {
            if (contextPtr != 0L) {
                Log.d(TAG, "Releasing whisper context for $currentModel")
                WhisperJni.freeContext(contextPtr)
                contextPtr = 0L
                currentModel = null
            }
        }
    }

    override fun isLoaded(): Boolean = contextPtr != 0L

    companion object {
        val MODELS = mapOf(
            "tiny" to "ggml-tiny.en.bin",
            "base" to "ggml-base.en.bin",
            "small" to "ggml-small.en.bin"
        )
    }
}
