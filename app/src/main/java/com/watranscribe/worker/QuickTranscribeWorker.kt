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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

private const val TAG = "QuickTranscribe"

/**
 * Triggered by the NotificationListenerService when a WhatsApp voice message is detected.
 * Waits for the file to land on disk, scans, then auto-transcribes ONLY the new file(s).
 */
@HiltWorker
class QuickTranscribeWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val transcriptionRepo: TranscriptionRepository,
    private val prefsRepo: PreferencesRepository,
    private val notifier: TranscriptionNotifier
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val sender = inputData.getString("sender") ?: "Unknown"
        Log.d(TAG, "Quick transcribe triggered for sender: $sender")

        val folderUri = prefsRepo.folderUri.first() ?: return Result.success()

        // Wait for WhatsApp to finish writing the .opus file
        delay(5_000)

        // Scan for new files — returns count of newly inserted entries
        val newCount = transcriptionRepo.scanForNewFiles(Uri.parse(folderUri))
        Log.d(TAG, "Found $newCount new files")

        if (newCount == 0) return Result.success()

        // Only transcribe the newest entries — getNewestPending returns by last_modified DESC
        val toTranscribe = transcriptionRepo.getNewestPending(newCount)
        Log.d(TAG, "Auto-transcribing ${toTranscribe.size} entries")

        for (entry in toTranscribe) {
            if (isStopped) break
            val label = entry.contact.ifBlank { sender }
            Log.d(TAG, "Transcribing: ${entry.filename} ($label)")

            val notifId = notifier.notifyProgress(label, "")
            try {
                val result = transcriptionRepo.transcribe(entry) { partial ->
                    notifier.updateProgress(notifId, label, partial)
                }
                notifier.cancelProgress(notifId)
                result.onSuccess { text ->
                    notifier.notifyComplete(label, text)
                }
                result.onFailure {
                    notifier.cancelProgress(notifId)
                }
            } catch (e: Exception) {
                notifier.cancelProgress(notifId)
                Log.e(TAG, "Failed to transcribe ${entry.filename}", e)
            }
        }

        return Result.success()
    }
}
