package com.watranscribe.worker

import android.app.PendingIntent
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
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
        Log.d(TAG, "Bulk: $total items, totalAudio=${totalAudioMs / 1000}s")
        if (total == 0) return Result.success()

        try {
            setForeground(buildForegroundInfo(0, total, "Starting…"))
        } catch (e: Exception) {
            Log.w(TAG, "setForeground failed (notification permission?)", e)
        }

        var processedAudioMs = 0L
        var processedWallMs = 0L

        for ((i, entry) in items.withIndex()) {
            if (isStopped) {
                Log.d(TAG, "Bulk stopped by user at ${i}/$total")
                break
            }
            val current = i + 1
            val label = entry.contact.ifBlank { entry.filename }

            try {
                setForeground(buildForegroundInfo(current, total, label))
            } catch (_: Exception) { /* keep going */ }

            setProgress(workDataOf(
                KEY_CURRENT to current,
                KEY_TOTAL to total,
                KEY_FILENAME to entry.filename,
                KEY_CONTACT to entry.contact,
                KEY_TOTAL_AUDIO_MS to totalAudioMs,
                KEY_PROCESSED_AUDIO_MS to processedAudioMs,
                KEY_PROCESSED_WALL_MS to processedWallMs,
                KEY_STARTED_AT to startedAt,
            ))

            val fileStart = System.currentTimeMillis()
            try {
                transcriptionRepo.transcribe(entry, modelOverride = target)
                processedAudioMs += safeDurationMs(entry.durationMs)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // transcribe() already marks the row FAILED on exception; just log and continue.
                // Don't credit failed-file audio toward the rate — it produced no useful work.
                Log.e(TAG, "Bulk: failed ${entry.filename}", e)
            }
            processedWallMs += System.currentTimeMillis() - fileStart
        }

        Log.d(TAG, "Bulk complete in ${(System.currentTimeMillis() - startedAt) / 1000}s")
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
        private const val MAX_REASONABLE_DURATION_MS = 21_600_000L // 6 hours
    }
}
