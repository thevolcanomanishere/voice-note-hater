package com.watranscribe.ui.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.watranscribe.data.local.TranscriptionStatus
import com.watranscribe.data.repository.PreferencesRepository
import com.watranscribe.data.repository.TranscriptionRepository
import android.util.Log
import com.watranscribe.engine.ModelInfo
import com.watranscribe.engine.ModelManager
import com.watranscribe.engine.ReleaseInfo
import com.watranscribe.engine.TranscriptionEngine
import com.watranscribe.engine.TranscriptionNotifier
import com.watranscribe.engine.UpdateChecker
import com.watranscribe.worker.BulkTranscribeWorker
import java.io.File
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class UpdateUiState {
    object Idle : UpdateUiState()
    object Checking : UpdateUiState()
    data class UpToDate(val currentVersion: String) : UpdateUiState()
    data class Available(val release: ReleaseInfo) : UpdateUiState()
    data class Downloading(val release: ReleaseInfo, val progress: Float) : UpdateUiState()
    data class Downloaded(val release: ReleaseInfo, val apk: File) : UpdateUiState()
    data class Error(val message: String) : UpdateUiState()
}

data class BulkProgress(
    val current: Int,
    val total: Int,
    val filename: String,
    val contact: String,
    /** Estimated milliseconds remaining, or null while still warming up (no data yet). */
    val etaMs: Long?,
    /** Estimated wall-clock completion time as epoch ms, or null while warming up. */
    val finishAtEpochMs: Long?,
    /** Audio processed so far in ms (files with known duration only). */
    val processedAudioMs: Long,
    /** Total audio in ms for all files in this run (files with known duration only). */
    val totalAudioMs: Long,
    /** Real-time factor: wall_ms / audio_ms. < 1.0 = faster than realtime. Null until first file. */
    val rtf: Double?,
    /** Epoch ms when the current file started (for elapsed-time display in the UI). */
    val fileStartedAtMs: Long,
    /** Partial transcription text streaming in from the current file. */
    val liveText: String,
    val thermalStatus: Int = 0,
    val cpuMaxMhz: Int = 0,
    val batteryTempC: Float = 0f,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val prefsRepo: PreferencesRepository,
    private val transcriptionRepo: TranscriptionRepository,
    private val notifier: TranscriptionNotifier,
    val modelManager: ModelManager,
    private val updateChecker: UpdateChecker,
    private val engines: Map<String, @JvmSuppressWildcards TranscriptionEngine>,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    val currentVersion: String =
        "${updateChecker.currentVersion} (${updateChecker.currentSha})"

    private val _updateState = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    val updateState: StateFlow<UpdateUiState> = _updateState.asStateFlow()

    val folderUri = prefsRepo.folderUri.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), null
    )

    private val _folderResolutionHint = MutableStateFlow<String?>(null)
    val folderResolutionHint: StateFlow<String?> = _folderResolutionHint.asStateFlow()

    val modelSize = prefsRepo.modelSize.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), "base.en"
    )

    val backgroundScanEnabled = prefsRepo.backgroundScanEnabled.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), true
    )

    private val _transcriptionCount = MutableStateFlow(0)
    val transcriptionCount: StateFlow<Int> = _transcriptionCount.asStateFlow()

    private val _downloadedModels = MutableStateFlow<Set<String>>(emptySet())
    val downloadedModels: StateFlow<Set<String>> = _downloadedModels.asStateFlow()

    val downloadProgress = modelManager.downloadProgress

    private val _storageUsed = MutableStateFlow(0L)
    val storageUsed: StateFlow<Long> = _storageUsed.asStateFlow()

    // --- Bulk transcribe stats (live from Room) ---
    private data class StatusBucket(val count: Int, val durationMs: Long)
    private val statsFlow: StateFlow<Map<TranscriptionStatus, StatusBucket>> =
        transcriptionRepo.observeStatusStats()
            .map { rows -> rows.associate { it.status to StatusBucket(it.count, it.durationMs) } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    val pendingCount: StateFlow<Int> = statsFlow
        .map { it[TranscriptionStatus.PENDING]?.count ?: 0 }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)
    val failedCount: StateFlow<Int> = statsFlow
        .map { it[TranscriptionStatus.FAILED]?.count ?: 0 }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)
    val completedCount: StateFlow<Int> = statsFlow
        .map { it[TranscriptionStatus.COMPLETED]?.count ?: 0 }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)
    val pendingDurationMs: StateFlow<Long> = statsFlow
        .map { it[TranscriptionStatus.PENDING]?.durationMs ?: 0L }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)
    val failedDurationMs: StateFlow<Long> = statsFlow
        .map { it[TranscriptionStatus.FAILED]?.durationMs ?: 0L }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    // --- Bulk model picker (in-memory only — does NOT mutate global pref) ---
    private val _bulkModelId = MutableStateFlow<String?>(null)
    val bulkModelId: StateFlow<String?> = _bulkModelId.asStateFlow()

    fun selectBulkModel(id: String) { _bulkModelId.value = id }

    // --- Bulk job state derived from WorkManager ---
    private val workManager = WorkManager.getInstance(appContext)

    private val bulkWorkInfo: StateFlow<WorkInfo?> = workManager
        .getWorkInfosForUniqueWorkFlow(BulkTranscribeWorker.WORK_NAME)
        .map { it.firstOrNull() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val isBulkRunning: StateFlow<Boolean> = bulkWorkInfo
        .map { it?.state == WorkInfo.State.RUNNING || it?.state == WorkInfo.State.ENQUEUED }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val bulkProgress: StateFlow<BulkProgress?> = bulkWorkInfo
        .map { wi ->
            if (wi == null || wi.state != WorkInfo.State.RUNNING) return@map null
            val p = wi.progress
            val total = p.getInt(BulkTranscribeWorker.KEY_TOTAL, 0)
            if (total == 0) return@map null
            val current = p.getInt(BulkTranscribeWorker.KEY_CURRENT, 0)
            val totalAudioMs = p.getLong(BulkTranscribeWorker.KEY_TOTAL_AUDIO_MS, 0L)
            val processedAudioMs = p.getLong(BulkTranscribeWorker.KEY_PROCESSED_AUDIO_MS, 0L)
            val processedWallMs = p.getLong(BulkTranscribeWorker.KEY_PROCESSED_WALL_MS, 0L)

            // Compute ETA from per-file rate: rate = wall_ms / audio_ms (i.e. RTF + overhead).
            // Need at least one completed file with non-zero audio to have a meaningful rate;
            // otherwise leave eta null and the UI shows "estimating…".
            val (etaMs, finishAt) = if (processedAudioMs > 0L && processedWallMs > 0L) {
                val remainingAudioMs = (totalAudioMs - processedAudioMs).coerceAtLeast(0L)
                val rate = processedWallMs.toDouble() / processedAudioMs.toDouble()
                val eta = (remainingAudioMs * rate).toLong()
                eta to (System.currentTimeMillis() + eta)
            } else {
                null to null
            }

            BulkProgress(
                current = current,
                total = total,
                filename = p.getString(BulkTranscribeWorker.KEY_FILENAME).orEmpty(),
                contact = p.getString(BulkTranscribeWorker.KEY_CONTACT).orEmpty(),
                etaMs = etaMs,
                finishAtEpochMs = finishAt,
                processedAudioMs = processedAudioMs,
                totalAudioMs = totalAudioMs,
                rtf = if (processedAudioMs > 0L && processedWallMs > 0L)
                    processedWallMs.toDouble() / processedAudioMs.toDouble()
                else null,
                fileStartedAtMs = p.getLong(BulkTranscribeWorker.KEY_FILE_STARTED_AT, 0L),
                liveText = p.getString(BulkTranscribeWorker.KEY_LIVE_TEXT).orEmpty(),
                thermalStatus = p.getInt(BulkTranscribeWorker.KEY_THERMAL, 0),
                cpuMaxMhz = p.getInt(BulkTranscribeWorker.KEY_CPU_MHZ, 0),
                batteryTempC = p.getFloat(BulkTranscribeWorker.KEY_BATTERY_TEMP, 0f),
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    init {
        viewModelScope.launch {
            _transcriptionCount.value = transcriptionRepo.count()
            refreshDownloadedModels()
        }

        viewModelScope.launch {
            folderUri.collect { uriStr ->
                if (uriStr.isNullOrBlank()) {
                    _folderResolutionHint.value = null
                    return@collect
                }
                val uri = runCatching { Uri.parse(uriStr) }.getOrNull()
                if (uri == null) {
                    _folderResolutionHint.value = null
                    return@collect
                }
                val hint = runCatching { transcriptionRepo.getFolderResolutionHint(uri) }.getOrNull()
                _folderResolutionHint.value = hint
            }
        }
    }

    private fun refreshDownloadedModels() {
        _downloadedModels.value = modelManager.getDownloadedModels().map { it.id }.toSet()
        _storageUsed.value = modelManager.getStorageUsed()
    }

    fun onFolderSelected(uri: Uri) {
        viewModelScope.launch {
            prefsRepo.setFolderUri(uri.toString())
            _folderResolutionHint.value = runCatching {
                transcriptionRepo.getFolderResolutionHint(uri)
            }.getOrNull()
        }
    }

    fun onModelSelected(model: ModelInfo) {
        viewModelScope.launch {
            Log.d("SettingsVM", "onModelSelected id=${model.id} engine=${model.engine} file=${model.filename}")
            prefsRepo.setModelSize(model.id)
            // Warm up the selected engine for the next transcription.
            // Do NOT release the other engine here — a transcription may be in flight and
            // freeing its native context mid-inference causes a SIGSEGV. The next engine
            // load inside that engine will release its own prior model safely under a mutex.
            val engine = engines[model.engine]
            if (engine == null) {
                Log.e("SettingsVM", "No engine registered for '${model.engine}'")
                return@launch
            }
            try {
                engine.loadModel(model.filename)
                Log.d("SettingsVM", "Loaded ${model.id} on ${model.engine}")
            } catch (t: Throwable) {
                Log.e("SettingsVM", "Failed to load ${model.id}", t)
            }
        }
    }

    fun downloadModel(model: ModelInfo) {
        viewModelScope.launch {
            modelManager.downloadModel(model)
            refreshDownloadedModels()
        }
    }

    fun deleteModel(model: ModelInfo) {
        modelManager.deleteModel(model)
        refreshDownloadedModels()
    }

    fun onBackgroundScanToggled(enabled: Boolean) {
        viewModelScope.launch { prefsRepo.setBackgroundScan(enabled) }
    }

    fun startBulk() {
        val modelId = _bulkModelId.value ?: modelSize.value
        Log.d("SettingsVM", "startBulk model=$modelId")
        val req = OneTimeWorkRequestBuilder<BulkTranscribeWorker>()
            .setInputData(workDataOf(BulkTranscribeWorker.KEY_MODEL_ID to modelId))
            .build()
        workManager.enqueueUniqueWork(
            BulkTranscribeWorker.WORK_NAME,
            ExistingWorkPolicy.KEEP,
            req,
        )
    }

    fun stopBulk() {
        Log.d("SettingsVM", "stopBulk")
        workManager.cancelUniqueWork(BulkTranscribeWorker.WORK_NAME)
    }

    fun clearAllTranscriptions() {
        viewModelScope.launch {
            val cleared = transcriptionRepo.clearAllTranscriptions()
            _transcriptionCount.value = transcriptionRepo.count()
            Log.d("SettingsVM", "Cleared $cleared transcriptions")
        }
    }

    fun checkForUpdate() {
        viewModelScope.launch {
            _updateState.value = UpdateUiState.Checking
            _updateState.value = try {
                val release = updateChecker.fetchLatestRelease()
                when {
                    release == null -> UpdateUiState.UpToDate(currentVersion)
                    updateChecker.isNewer(release) -> UpdateUiState.Available(release)
                    else -> UpdateUiState.UpToDate(currentVersion)
                }
            } catch (t: Throwable) {
                Log.e("SettingsVM", "Update check failed", t)
                UpdateUiState.Error(t.message ?: "Check failed")
            }
        }
    }

    fun downloadUpdate() {
        val release = when (val s = _updateState.value) {
            is UpdateUiState.Available -> s.release
            is UpdateUiState.Error -> return
            else -> return
        }
        viewModelScope.launch {
            _updateState.value = UpdateUiState.Downloading(release, 0f)
            _updateState.value = try {
                val apk = updateChecker.downloadApk(release) { frac ->
                    _updateState.value = UpdateUiState.Downloading(release, frac)
                }
                UpdateUiState.Downloaded(release, apk)
            } catch (t: Throwable) {
                Log.e("SettingsVM", "Update download failed", t)
                UpdateUiState.Error(t.message ?: "Download failed")
            }
        }
    }

    fun installUpdate() {
        val state = _updateState.value as? UpdateUiState.Downloaded ?: return
        if (!updateChecker.canRequestInstall()) {
            updateChecker.openInstallPermissionSettings()
            return
        }
        updateChecker.launchInstall(state.apk)
    }

    fun dismissUpdate() {
        _updateState.value = UpdateUiState.Idle
    }

    fun testNotification(type: String) {
        viewModelScope.launch {
            when (type) {
                "short" -> notifier.notifyComplete("Mum", "Can you pick up some milk on the way home please?")
                "long" -> notifier.notifyComplete("Dave @ Work Chat", "So basically what happened was the deployment failed because someone pushed to main without running the tests first and then the CI pipeline broke and we had to roll back everything which took about two hours and now we're behind on the sprint and the product manager is not happy about it at all. Anyway can you review my PR when you get a chance?")
                "progress" -> {
                    val id = notifier.notifyProgress("Sarah @ Family", "")
                    val words = "Yeah so I was thinking we could do dinner on Saturday instead because Sunday is going to be really busy with the kids football and everything else going on".split(" ")
                    var accumulated = ""
                    for (word in words) {
                        accumulated += "$word "
                        notifier.updateProgress(id, "Sarah @ Family", accumulated.trim())
                        delay(200)
                    }
                    notifier.cancelProgress(id)
                    notifier.notifyComplete("Sarah @ Family", accumulated.trim())
                }
            }
        }
    }
}
