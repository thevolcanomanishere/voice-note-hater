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
import kotlinx.coroutines.flow.first

private const val TAG = "TranscriptionWorker"

/**
 * Safety-net periodic scan for voice notes the notification listener might have
 * missed (e.g. the OS froze our process before the notification was delivered).
 * This worker only scans/inserts files. Transcription is user-initiated from the
 * list (single tap) or Settings bulk action.
 */
@HiltWorker
class TranscriptionWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val transcriptionRepo: TranscriptionRepository,
    private val prefsRepo: PreferencesRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val folderUri = prefsRepo.folderUri.first() ?: return Result.success()
        val backgroundEnabled = prefsRepo.backgroundScanEnabled.first()
        if (!backgroundEnabled) return Result.success()

        val newCount = transcriptionRepo.scanForNewFiles(Uri.parse(folderUri))
        Log.d(TAG, "scan found $newCount new files")

        return Result.success()
    }

    companion object {
        const val WORK_NAME = "transcription_scan"
    }
}
