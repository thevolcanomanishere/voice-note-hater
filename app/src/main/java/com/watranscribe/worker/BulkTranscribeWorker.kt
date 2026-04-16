package com.watranscribe.worker

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import java.io.File
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.watranscribe.R
import com.watranscribe.data.repository.TranscriptionRepository
import com.watranscribe.engine.ModelManager
import com.watranscribe.engine.TranscriptionNotifier
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TAG = "BulkTranscribe"

/**
 * User-initiated bulk run over every PENDING + FAILED row, transcribed with a
 * specific model chosen for THIS run only (no preference mutation). Long runs
 * (hundreds of files) would be killed by Doze inside ~10 min as a regular
 * background worker, so this runs as a foreground service-backed worker.
 *
 * Stop is wired through WorkManager's standard cancellation path: the
 * notification action and the in-app Stop button both cancel the unique work,
 * which flips [isStopped] and the loop bails after the current file finishes.
 */
@HiltWorker
class BulkTranscribeWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val transcriptionRepo: TranscriptionRepository,
    private val modelManager: ModelManager,
) : CoroutineWorker(context, params) {

    private fun safeDurationMs(raw: Long): Long {
        return if (raw in 1L..MAX_REASONABLE_DURATION_MS) raw else 0L
    }

    override suspend fun doWork(): Result {
        try {
            return runBulk()
        } finally {
            // Defensive: WorkManager normally clears the foreground notification when the
            // worker exits, but mid-cancel can leave it stuck. Cancel explicitly so the user
            // never sees a lingering "Bulk transcribe" notification after Stop or completion.
            NotificationManagerCompat.from(applicationContext).cancel(NOTIF_ID)
        }
    }

    private suspend fun runBulk(): Result {
        val modelId = inputData.getString(KEY_MODEL_ID)
        val target = ModelManager.AVAILABLE_MODELS.find { it.id == modelId }
            ?.takeIf { modelManager.isDownloaded(it) }
            ?: ModelManager.AVAILABLE_MODELS.first { it.id == "base.en" }
        Log.d(TAG, "Bulk start: model=${target.id} (requested=$modelId)")

        // Snapshot up-front so the displayed total is deterministic — new files
        // arriving mid-run will be picked up by the next bulk or by the quick worker.
        val items = transcriptionRepo.getPendingAndFailed()
        val total = items.size
        val totalAudioMs = items.sumOf { safeDurationMs(it.durationMs) }
        val startedAt = System.currentTimeMillis()
        val filesWithNoDuration = items.count { it.durationMs == 0L }
        Log.d(TAG, "Bulk: $total items, totalAudio=${totalAudioMs / 1000}s, zeroDuration=$filesWithNoDuration")
        if (total == 0) return Result.success()

        try {
            setForeground(buildForegroundInfo(0, total, "Starting…"))
        } catch (e: Exception) {
            Log.w(TAG, "setForeground failed (notification permission?)", e)
        }

        // Pre-warm the model so the first file doesn't pay load cost in its wall time.
        // Without this, the ETA rate is inflated by model-load time in file #1, making
        // ETA estimates too high for the whole run.
        val warmStart = System.currentTimeMillis()
        transcriptionRepo.preWarmModel(target)
        val warmMs = System.currentTimeMillis() - warmStart
        Log.d(TAG, "Bulk: model warm-up done in ${warmMs}ms")

        var processedAudioMs = 0L
        var processedWallMs = 0L
        var successCount = 0
        var failCount = 0

        // Shared state written by onSegment callback (JNI thread) and read by the ticker.
        // Use AtomicReference/AtomicLong for safe cross-thread visibility without @Volatile
        // (which only applies to fields, not local variables in Kotlin).
        val currentLiveText = java.util.concurrent.atomic.AtomicReference("")
        val currentFileStartedAt = java.util.concurrent.atomic.AtomicLong(0L)
        val currentFilename = java.util.concurrent.atomic.AtomicReference("")
        val currentContact = java.util.concurrent.atomic.AtomicReference("")
        val currentIndex = java.util.concurrent.atomic.AtomicInteger(0)

        // A ticker coroutine runs alongside the main loop, pushing setProgress every 600ms.
        // This keeps the UI alive during long inferences — without it, nothing updates between
        // the start-of-file setProgress and the next file's setProgress (can be 30-60s).
        coroutineScope {
            val tickerJob = launch {
                while (true) {
                    delay(600)
                    setProgress(workDataOf(
                        KEY_CURRENT to currentIndex.get(),
                        KEY_TOTAL to total,
                        KEY_FILENAME to currentFilename.get(),
                        KEY_CONTACT to currentContact.get(),
                        KEY_TOTAL_AUDIO_MS to totalAudioMs,
                        KEY_PROCESSED_AUDIO_MS to processedAudioMs,
                        KEY_PROCESSED_WALL_MS to processedWallMs,
                        KEY_STARTED_AT to startedAt,
                        KEY_FILE_STARTED_AT to currentFileStartedAt.get(),
                        KEY_LIVE_TEXT to currentLiveText.get(),
                        KEY_THERMAL to thermalStatus(),
                        KEY_CPU_MHZ to cpuMaxMhz(),
                        KEY_BATTERY_TEMP to batteryTempC(),
                    ))
                }
            }

            for ((i, entry) in items.withIndex()) {
                if (isStopped) {
                    Log.d(TAG, "Bulk stopped by user at ${i}/$total")
                    break
                }
                val current = i + 1
                currentIndex.set(current)
                currentFilename.set(entry.filename)
                currentContact.set(entry.contact)
                currentLiveText.set("")
                currentFileStartedAt.set(System.currentTimeMillis())

                val label = entry.contact.ifBlank { entry.filename }
                try {
                    setForeground(buildForegroundInfo(current, total, label))
                } catch (_: Exception) { /* keep going */ }

                // Emit immediately so the UI shows the new file before inference starts.
                setProgress(workDataOf(
                    KEY_CURRENT to current,
                    KEY_TOTAL to total,
                    KEY_FILENAME to entry.filename,
                    KEY_CONTACT to entry.contact,
                    KEY_TOTAL_AUDIO_MS to totalAudioMs,
                    KEY_PROCESSED_AUDIO_MS to processedAudioMs,
                    KEY_PROCESSED_WALL_MS to processedWallMs,
                    KEY_STARTED_AT to startedAt,
                    KEY_FILE_STARTED_AT to currentFileStartedAt.get(),
                    KEY_LIVE_TEXT to "",
                ))

                Log.d(TAG, "Bulk[$current/$total] start: ${entry.filename} audio=${entry.durationMs}ms contact='${entry.contact}'")
                val fileStart = System.currentTimeMillis()
                val result = try {
                    transcriptionRepo.transcribe(entry, modelOverride = target) { text ->
                        currentLiveText.set(text)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Bulk: failed ${entry.filename}", e)
                    kotlin.Result.failure(e)
                }
                val fileWallMs = System.currentTimeMillis() - fileStart
                val rtf = if (entry.durationMs > 0) fileWallMs.toDouble() / entry.durationMs else Double.NaN
                if (result.isSuccess) {
                    successCount++
                    processedAudioMs += safeDurationMs(entry.durationMs)
                    processedWallMs += fileWallMs
                    Log.d(TAG, "Bulk[$current/$total] ok: wall=${fileWallMs}ms audio=${entry.durationMs}ms rtf=%.2fx".format(rtf))
                } else {
                    failCount++
                    Log.w(TAG, "Bulk[$current/$total] fail: wall=${fileWallMs}ms ${entry.filename}")
                }
            }

            tickerJob.cancel()
        }

        val totalWallMs = System.currentTimeMillis() - startedAt
        val aggRtf = if (processedAudioMs > 0) processedWallMs.toDouble() / processedAudioMs else Double.NaN
        Log.d(TAG, "Bulk done: ${successCount}ok ${failCount}fail in ${totalWallMs / 1000}s " +
            "(warm=${warmMs}ms, transcribeWall=${processedWallMs / 1000}s, audio=${processedAudioMs / 1000}s, aggRTF=%.2fx)".format(aggRtf))
        return Result.success()
    }

    private fun buildForegroundInfo(current: Int, total: Int, label: String): ForegroundInfo {
        val stopIntent: PendingIntent = WorkManager.getInstance(applicationContext)
            .createCancelPendingIntent(id)

        val notification = NotificationCompat.Builder(applicationContext, TranscriptionNotifier.PROGRESS_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Bulk transcribe")
            .setContentText(if (total > 0) "$current / $total — $label" else label)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setProgress(total.coerceAtLeast(1), current, false)
            .addAction(0, "Stop", stopIntent)
            .build()

        return if (Build.VERSION.SDK_INT >= 34) {
            ForegroundInfo(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIF_ID, notification)
        }
    }

    private fun thermalStatus(): Int {
        if (Build.VERSION.SDK_INT < 29) return 0
        val pm = applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.currentThermalStatus
    }

    private fun cpuMaxMhz(): Int {
        var max = 0; var cpu = 0
        while (true) {
            val f = File("/sys/devices/system/cpu/cpu$cpu/cpufreq/scaling_cur_freq")
            if (!f.exists()) break
            try { max = maxOf(max, f.readText().trim().toInt() / 1000) } catch (_: Exception) {}
            cpu++
        }
        return max
    }

    private fun batteryTempC(): Float {
        val intent = applicationContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return 0f
        return intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f
    }

    companion object {
        const val WORK_NAME = "bulk_transcribe"
        const val NOTIF_ID = 9001
        const val KEY_MODEL_ID = "model_id"
        const val KEY_CURRENT = "current"
        const val KEY_TOTAL = "total"
        const val KEY_FILENAME = "filename"
        const val KEY_CONTACT = "contact"
        const val KEY_TOTAL_AUDIO_MS = "total_audio_ms"
        const val KEY_PROCESSED_AUDIO_MS = "processed_audio_ms"
        const val KEY_PROCESSED_WALL_MS = "processed_wall_ms"
        const val KEY_STARTED_AT = "started_at"
        const val KEY_FILE_STARTED_AT = "file_started_at"
        const val KEY_LIVE_TEXT = "live_text"
        const val KEY_THERMAL = "thermal"
        const val KEY_CPU_MHZ = "cpu_mhz"
        const val KEY_BATTERY_TEMP = "battery_temp"
        private const val MAX_REASONABLE_DURATION_MS = 21_600_000L // 6 hours
    }
}
