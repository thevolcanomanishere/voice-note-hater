package com.watranscribe.worker

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.watranscribe.data.repository.PreferencesRepository
import com.watranscribe.data.repository.TranscriptionRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

private const val TAG = "QuickTranscribe"

/**
 * Triggered by the NotificationListenerService when a WhatsApp voice message is detected.
 * Waits for the file to land on disk, then scans/inserts so it appears in the list.
 * Transcription remains user-initiated.
 */
@HiltWorker
class QuickTranscribeWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val transcriptionRepo: TranscriptionRepository,
    private val prefsRepo: PreferencesRepository,
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

        if (newCount > 0) {
            Log.d(TAG, "Inserted $newCount new file(s) for manual transcription")
        }

        return Result.success()
    }
}
