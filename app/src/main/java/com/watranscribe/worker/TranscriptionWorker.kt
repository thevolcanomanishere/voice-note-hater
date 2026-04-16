package com.watranscribe.worker

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.watranscribe.data.repository.PreferencesRepository
import com.watranscribe.data.repository.TranscriptionRepository
import com.watranscribe.engine.TranscriptionNotifier
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first

private const val TAG = "TranscriptionWorker"

/**
 * Safety-net periodic scan for voice notes the notification listener might have
 * missed (e.g. the OS froze our process before the notification was delivered).
 * Only runs if the user hasn't disabled background scanning. For freshly-scanned
 * items we post notifications the same way [QuickTranscribeWorker] does — old
 * pending rows are transcribed silently so the notification tray isn't spammed
 * with backfill when the user hits "Scan now" manually.
 */
@HiltWorker
class TranscriptionWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val transcriptionRepo: TranscriptionRepository,
    private val prefsRepo: PreferencesRepository,
    private val notifier: TranscriptionNotifier,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val folderUri = prefsRepo.folderUri.first() ?: return Result.success()
        val backgroundEnabled = prefsRepo.backgroundScanEnabled.first()
        if (!backgroundEnabled) return Result.success()

        val newCount = transcriptionRepo.scanForNewFiles(Uri.parse(folderUri))
        Log.d(TAG, "scan found $newCount new files")

        // Only notify for files inserted during THIS scan.
        val freshFilenames = if (newCount > 0) {
            transcriptionRepo.getNewestPending(newCount).map { it.filename }.toSet()
        } else emptySet()

        val pending = transcriptionRepo.getPending()
        for (entry in pending) {
            if (isStopped) break

            val notifyThis = entry.filename in freshFilenames
            val label = entry.contact.ifBlank { entry.filename }
            val notifId = if (notifyThis) notifier.notifyProgress(label, "") else -1

            try {
                val result = if (notifyThis) {
                    transcriptionRepo.transcribe(entry) { partial ->
                        notifier.updateProgress(notifId, label, partial)
                    }
                } else {
                    transcriptionRepo.transcribe(entry)
                }

                if (notifyThis) {
                    notifier.cancelProgress(notifId)
                    result.onSuccess { text -> notifier.notifyComplete(label, text) }
                }
            } catch (e: Exception) {
                if (notifyThis) notifier.cancelProgress(notifId)
                Log.e(TAG, "transcribe failed for ${entry.filename}", e)
            }
        }

        return Result.success()
    }

    companion object {
        const val WORK_NAME = "transcription_scan"
    }
}
