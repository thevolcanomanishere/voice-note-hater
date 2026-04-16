package com.watranscribe.engine

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "Benchmarker"

data class BenchmarkResult(
    val modelId: String,
    val modelName: String,
    val modelSizeMb: Int,
    val engineId: String,
    val audioSeconds: Double,
    val loadMs: Long,
    val decodeMs: Long,
    val inferenceMs: Long,
    val totalMs: Long,
    val rtf: Double, // real-time factor: <1 = faster than realtime
    val segments: Int,
    val transcript: String,
    // Device state at inference time
    val thermalStatus: Int = 0,   // PowerManager.THERMAL_STATUS_* (0=none)
    val cpuMaxMhz: Int = 0,       // highest current freq across all cores (0 = unreadable)
    val batteryTempC: Float = 0f  // degrees Celsius (0 = unreadable)
)

@Singleton
class Benchmarker @Inject constructor(
    @ApplicationContext private val context: Context,
    private val engines: Map<String, @JvmSuppressWildcards TranscriptionEngine>,
    private val audioDecoder: AudioDecoder,
    private val modelManager: ModelManager
) {
    private fun readThermalStatus(): Int {
        if (Build.VERSION.SDK_INT < 29) return 0
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.currentThermalStatus
    }

    private fun readCpuMaxMhz(): Int {
        // Read current frequency for each core and return the max.
        var max = 0
        var cpu = 0
        while (true) {
            val f = File("/sys/devices/system/cpu/cpu$cpu/cpufreq/scaling_cur_freq")
            if (!f.exists()) break
            try { max = maxOf(max, f.readText().trim().toInt() / 1000) } catch (_: Exception) {}
            cpu++
        }
        return max
    }

    private fun readBatteryTempC(): Float {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return 0f
        val temp = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)
        return temp / 10f
    }

    /**
     * Benchmark a single model against a given audio URI.
     * Returns null if the model isn't downloaded or its engine isn't registered.
     */
    suspend fun benchmarkModel(model: ModelInfo, audioUri: Uri): BenchmarkResult? {
        if (!modelManager.isDownloaded(model)) return null
        val engine = engines[model.engine] ?: run {
            Log.e(TAG, "No engine registered for ${model.engine} (model ${model.id})")
            return null
        }

        Log.d(TAG, "Benchmarking ${model.id} on engine=${model.engine}...")

        // Force unload all engines so we're measuring cold-load, not model swap.
        for (e in engines.values) e.release()

        // Load model
        val loadStart = System.nanoTime()
        try {
            engine.loadModel(model.filename)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load ${model.filename} on ${model.engine}", e)
            return null
        }
        val loadMs = (System.nanoTime() - loadStart) / 1_000_000

        // Decode audio
        val decodeStart = System.nanoTime()
        val decoded = audioDecoder.decode(audioUri)
        val decodeMs = (System.nanoTime() - decodeStart) / 1_000_000
        val audioSec = decoded.durationMs / 1000.0

        // Capture device state just before inference
        val thermalStatus = readThermalStatus()
        val cpuMaxMhz = readCpuMaxMhz()
        val batteryTempC = readBatteryTempC()

        // Inference
        val inferStart = System.nanoTime()
        val result = engine.transcribeWithTimings(decoded.samples, null)
        val inferMs = (System.nanoTime() - inferStart) / 1_000_000

        val totalMs = loadMs + decodeMs + inferMs
        val rtf = if (decoded.durationMs > 0) inferMs.toDouble() / decoded.durationMs else 0.0

        Log.d(TAG, "Benchmark ${model.id} (${model.engine}): load=${loadMs}ms decode=${decodeMs}ms infer=${inferMs}ms total=${totalMs}ms rtf=%.2fx thermal=$thermalStatus cpu=${cpuMaxMhz}MHz bat=${batteryTempC}°C".format(rtf))

        return BenchmarkResult(
            modelId = model.id,
            modelName = model.displayName,
            modelSizeMb = model.sizeMb,
            engineId = model.engine,
            audioSeconds = audioSec,
            loadMs = loadMs,
            decodeMs = decodeMs,
            inferenceMs = inferMs,
            totalMs = totalMs,
            rtf = rtf,
            segments = result.segments.size,
            transcript = result.text.trim(),
            thermalStatus = thermalStatus,
            cpuMaxMhz = cpuMaxMhz,
            batteryTempC = batteryTempC
        )
    }
}
