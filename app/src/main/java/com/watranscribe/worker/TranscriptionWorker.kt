package com.watranscribe.worker

import android.content.Context
import android.net.Uri
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.watranscribe.data.repository.PreferencesRepository
import com.watranscribe.data.repository.TranscriptionRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first

@HiltWorker
class TranscriptionWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val transcriptionRepo: TranscriptionRepository,
    private val prefsRepo: PreferencesRepository
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val folderUri = prefsRepo.folderUri.first() ?: return Result.success()
        val backgroundEnabled = prefsRepo.backgroundScanEnabled.first()
        if (!backgroundEnabled) return Result.success()

        // Scan for new files
        transcriptionRepo.scanForNewFiles(Uri.parse(folderUri))

        // Transcribe all pending
        val pending = transcriptionRepo.getPending()
        for (entry in pending) {
            if (isStopped) break
            transcriptionRepo.transcribe(entry)
        }

        return Result.success()
    }

    companion object {
        const val WORK_NAME = "transcription_scan"
    }
}
