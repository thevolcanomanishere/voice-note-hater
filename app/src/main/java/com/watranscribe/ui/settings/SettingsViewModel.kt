package com.watranscribe.ui.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.watranscribe.data.repository.PreferencesRepository
import com.watranscribe.data.repository.TranscriptionRepository
import com.watranscribe.engine.ModelInfo
import com.watranscribe.engine.ModelManager
import com.watranscribe.engine.TranscriptionNotifier
import com.watranscribe.engine.WhisperEngine
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
    private val whisperEngine: WhisperEngine
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
            prefsRepo.setModelSize(model.id)
            // Load the new model
            whisperEngine.loadModel(model.filename)
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
