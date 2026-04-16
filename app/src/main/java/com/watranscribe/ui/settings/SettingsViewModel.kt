package com.watranscribe.ui.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.watranscribe.data.repository.PreferencesRepository
import com.watranscribe.data.repository.TranscriptionRepository
import android.util.Log
import com.watranscribe.engine.ModelInfo
import com.watranscribe.engine.ModelManager
import com.watranscribe.engine.TranscriptionEngine
import com.watranscribe.engine.TranscriptionNotifier
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val prefsRepo: PreferencesRepository,
    private val transcriptionRepo: TranscriptionRepository,
    private val notifier: TranscriptionNotifier,
    val modelManager: ModelManager,
    private val engines: Map<String, @JvmSuppressWildcards TranscriptionEngine>
) : ViewModel() {

    val folderUri = prefsRepo.folderUri.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), null
    )

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

    init {
        viewModelScope.launch {
            _transcriptionCount.value = transcriptionRepo.count()
            refreshDownloadedModels()
        }
    }

    private fun refreshDownloadedModels() {
        _downloadedModels.value = modelManager.getDownloadedModels().map { it.id }.toSet()
        _storageUsed.value = modelManager.getStorageUsed()
    }

    fun onFolderSelected(uri: Uri) {
        viewModelScope.launch { prefsRepo.setFolderUri(uri.toString()) }
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
