package com.watranscribe.engine

import android.content.Context
import android.net.Uri
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "Benchmarker"

data class BenchmarkResult(
    val modelId: String,
    val modelName: String,
    val modelSizeMb: Int,
    val audioSeconds: Double,
    val loadMs: Long,
    val decodeMs: Long,
    val inferenceMs: Long,
    val totalMs: Long,
    val rtf: Double, // real-time factor: <1 = faster than realtime
    val segments: Int,
    val transcript: String
)

@Singleton
class Benchmarker @Inject constructor(
    @ApplicationContext private val context: Context,
    private val whisperEngine: WhisperEngine,
    private val audioDecoder: AudioDecoder,
    private val modelManager: ModelManager
) {
    /**
     * Benchmark a single model against a given audio URI.
     * Returns null if the model isn't downloaded.
     */
    suspend fun benchmarkModel(model: ModelInfo, audioUri: Uri): BenchmarkResult? {
        if (!modelManager.isDownloaded(model)) return null

        Log.d(TAG, "Benchmarking ${model.id}...")

        // Force unload current model
        whisperEngine.release()

        // Load model
        val loadStart = System.nanoTime()
        try {
            whisperEngine.loadModel(model.filename)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load ${model.filename}", e)
            return null
        }
        val loadMs = (System.nanoTime() - loadStart) / 1_000_000

        // Decode audio
        val decodeStart = System.nanoTime()
        val decoded = audioDecoder.decode(audioUri)
        val decodeMs = (System.nanoTime() - decodeStart) / 1_000_000
        val audioSec = decoded.durationMs / 1000.0

        // Inference
        val inferStart = System.nanoTime()
        val result = whisperEngine.transcribeWithTimings(decoded.samples, null)
        val inferMs = (System.nanoTime() - inferStart) / 1_000_000

        val totalMs = loadMs + decodeMs + inferMs
        val rtf = if (decoded.durationMs > 0) inferMs.toDouble() / decoded.durationMs else 0.0

        Log.d(TAG, "Benchmark ${model.id}: load=${loadMs}ms decode=${decodeMs}ms infer=${inferMs}ms total=${totalMs}ms rtf=%.2fx".format(rtf))

        return BenchmarkResult(
            modelId = model.id,
            modelName = model.displayName,
            modelSizeMb = model.sizeMb,
            audioSeconds = audioSec,
            loadMs = loadMs,
            decodeMs = decodeMs,
            inferenceMs = inferMs,
            totalMs = totalMs,
            rtf = rtf,
            segments = result.segments.size,
            transcript = result.text.trim()
        )
    }
}
