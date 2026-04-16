package com.watranscribe.engine

import com.watranscribe.data.local.TimedSegment
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * Common interface for on-device speech-to-text engines.
 *
 * Implementations must be thread-safe — callers load a model and transcribe
 * from WorkManager, UI, and benchmark paths concurrently.
 */
interface TranscriptionEngine {
    /** Short engine identifier, e.g. "whisper" or "moonshine". */
    val id: String

    /**
     * Load the model identified by [modelFilename] (whisper) or model id /
     * directory name (moonshine). Idempotent: loading the same model twice
     * in a row is a no-op.
     */
    suspend fun loadModel(modelFilename: String)

    /**
     * Run full transcription on 16 kHz mono float PCM samples.
     * @param segmentFlow optional flow for live partial segments.
     */
    suspend fun transcribeWithTimings(
        samples: FloatArray,
        segmentFlow: MutableSharedFlow<String>?
    ): TranscribeResult

    /**
     * Release native resources. Safe to call multiple times. Suspends so the
     * implementation can wait for any in-flight transcription to finish —
     * freeing native memory while inference is running would segfault.
     */
    suspend fun release()

    fun isLoaded(): Boolean

    fun getCurrentModelName(): String?
}

/** Top-level result type so both engines can return it. */
data class TranscribeResult(val text: String, val segments: List<TimedSegment>)
