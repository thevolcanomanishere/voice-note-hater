package com.watranscribe.ui.transcriptions

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.watranscribe.data.local.TranscriptionEntity
import com.watranscribe.data.local.TranscriptionStatus
import com.watranscribe.data.repository.PreferencesRepository
import com.watranscribe.data.repository.TranscriptionRepository
import com.watranscribe.engine.ModelInfo
import com.watranscribe.engine.ModelManager
import com.watranscribe.engine.TranscriptionNotifier
import com.watranscribe.engine.WhisperEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TranscriptionListViewModel @Inject constructor(
    private val repo: TranscriptionRepository,
    private val prefsRepo: PreferencesRepository,
    private val notifier: TranscriptionNotifier,
    private val modelManager: ModelManager,
    private val whisperEngine: WhisperEngine
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val transcriptions: StateFlow<List<TranscriptionEntity>> = _searchQuery
        .flatMapLatest { query ->
            if (query.isBlank()) repo.getAllTranscriptions()
            else repo.search(query)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val folders: StateFlow<List<String>> = repo.getConversationFolders()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    /** ID of the item currently being transcribed, for UI indication */
    private val _transcribingId = MutableStateFlow<Long?>(null)
    val transcribingId: StateFlow<Long?> = _transcribingId.asStateFlow()

    /** Live streaming text for the item being transcribed */
    private val _liveText = MutableStateFlow("")
    val liveText: StateFlow<String> = _liveText.asStateFlow()

    fun onSearchQueryChanged(query: String) {
        _searchQuery.value = query
    }

    fun scanNow() {
        viewModelScope.launch {
            _isScanning.value = true
            try {
                val uriStr = prefsRepo.folderUri.first()
                if (uriStr != null) {
                    repo.scanForNewFiles(Uri.parse(uriStr))
                }
            } finally {
                _isScanning.value = false
            }
        }
    }

    fun getDownloadedModels(): List<ModelInfo> = modelManager.getDownloadedModels()

    /** User taps a pending item to transcribe it */
    fun transcribeItem(entity: TranscriptionEntity) {
        if (entity.status != TranscriptionStatus.PENDING && entity.status != TranscriptionStatus.FAILED) return
        if (_transcribingId.value != null) return

        viewModelScope.launch {
            _transcribingId.value = entity.id
            _liveText.value = ""
            val label = entity.contact.ifBlank { entity.filename }
            val notifId = notifier.notifyProgress(label, "")
            try {
                val result = repo.transcribe(entity) { partialText ->
                    _liveText.value = partialText
                    notifier.updateProgress(notifId, label, partialText)
                }
                notifier.cancelProgress(notifId)
                result.onSuccess { text -> notifier.notifyComplete(label, text) }
            } finally {
                _transcribingId.value = null
                _liveText.value = ""
                notifier.cancelProgress(notifId)
            }
        }
    }

    /** Retranscribe a completed item with a specific model */
    fun retranscribeWith(entity: TranscriptionEntity, model: ModelInfo) {
        if (_transcribingId.value != null) return

        viewModelScope.launch {
            _transcribingId.value = entity.id
            _liveText.value = ""
            val label = entity.contact.ifBlank { entity.filename }
            val notifId = notifier.notifyProgress(label, "")
            try {
                // Force load the requested model
                whisperEngine.loadModel(model.filename)
                val result = repo.transcribe(
                    entity.copy(status = TranscriptionStatus.PENDING)
                ) { partialText ->
                    _liveText.value = partialText
                    notifier.updateProgress(notifId, label, partialText)
                }
                notifier.cancelProgress(notifId)
                result.onSuccess { text -> notifier.notifyComplete(label, text) }
            } finally {
                _transcribingId.value = null
                _liveText.value = ""
                notifier.cancelProgress(notifId)
            }
        }
    }
}
