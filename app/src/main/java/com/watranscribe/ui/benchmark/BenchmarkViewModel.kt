package com.watranscribe.ui.benchmark

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.watranscribe.data.local.TranscriptionDao
import com.watranscribe.data.local.TranscriptionEntity
import com.watranscribe.data.local.TranscriptionStatus
import com.watranscribe.engine.BenchmarkResult
import com.watranscribe.engine.Benchmarker
import com.watranscribe.engine.ModelManager
import com.watranscribe.engine.TranscriptionEngine
import com.watranscribe.data.repository.PreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AudioChoice(
    val filename: String,
    val uri: String,
    val durationMs: Long,
    val contact: String
) {
    val label: String get() = "${contact.ifBlank { filename }} (${durationMs / 1000}s)"
}

@HiltViewModel
class BenchmarkViewModel @Inject constructor(
    private val benchmarker: Benchmarker,
    private val modelManager: ModelManager,
    private val engines: Map<String, @JvmSuppressWildcards TranscriptionEngine>,
    private val prefsRepo: PreferencesRepository,
    private val dao: TranscriptionDao
) : ViewModel() {

    private val _results = MutableStateFlow<List<BenchmarkResult>>(emptyList())
    val results: StateFlow<List<BenchmarkResult>> = _results.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _currentModel = MutableStateFlow<String?>(null)
    val currentModel: StateFlow<String?> = _currentModel.asStateFlow()

    private val _progress = MutableStateFlow("")
    val progress: StateFlow<String> = _progress.asStateFlow()

    private val _audioChoices = MutableStateFlow<List<AudioChoice>>(emptyList())
    val audioChoices: StateFlow<List<AudioChoice>> = _audioChoices.asStateFlow()

    private val _selectedAudio = MutableStateFlow<AudioChoice?>(null)
    val selectedAudio: StateFlow<AudioChoice?> = _selectedAudio.asStateFlow()

    private val _downloadedModels = MutableStateFlow<List<com.watranscribe.engine.ModelInfo>>(emptyList())
    val downloadedModels: StateFlow<List<com.watranscribe.engine.ModelInfo>> = _downloadedModels.asStateFlow()

    /** Set of model IDs the user has chosen to include in the next run. */
    private val _selectedModelIds = MutableStateFlow<Set<String>>(emptySet())
    val selectedModelIds: StateFlow<Set<String>> = _selectedModelIds.asStateFlow()

    init {
        viewModelScope.launch { loadAudioChoices() }
        refreshDownloadedModels()
    }

    private fun refreshDownloadedModels() {
        val downloaded = modelManager.getDownloadedModels()
        _downloadedModels.value = downloaded
        // Default: all downloaded models selected.
        if (_selectedModelIds.value.isEmpty()) {
            _selectedModelIds.value = downloaded.map { it.id }.toSet()
        } else {
            // Drop ids that are no longer downloaded.
            _selectedModelIds.value = _selectedModelIds.value intersect downloaded.map { it.id }.toSet()
        }
    }

    fun toggleModel(id: String) {
        _selectedModelIds.update { current ->
            if (id in current) current - id else current + id
        }
    }

    fun selectAllModels() {
        _selectedModelIds.value = _downloadedModels.value.map { it.id }.toSet()
    }

    fun clearModelSelection() {
        _selectedModelIds.value = emptySet()
    }

    private suspend fun loadAudioChoices() {
        val completed = dao.getByStatus(TranscriptionStatus.COMPLETED)
            .filter { it.durationMs > 0 }
            .sortedByDescending { it.lastModified }
            .take(20)
            .map { AudioChoice(it.filename, it.uri, it.durationMs, it.contact) }
        _audioChoices.value = completed
        // Auto-select a good candidate (10-30s preferred)
        _selectedAudio.value = completed
            .filter { it.durationMs in 8_000..35_000 }
            .firstOrNull()
            ?: completed.firstOrNull()
    }

    fun selectAudio(choice: AudioChoice) {
        _selectedAudio.value = choice
    }

    fun runBenchmark() {
        if (_isRunning.value) return
        val audio = _selectedAudio.value ?: return

        viewModelScope.launch {
            _isRunning.value = true
            _results.value = emptyList()

            val audioUri = Uri.parse(audio.uri)
            val picked = _selectedModelIds.value
            val downloaded = modelManager.getDownloadedModels().filter { it.id in picked }
            if (downloaded.isEmpty()) {
                _progress.value = "Pick at least one model to benchmark."
                _isRunning.value = false
                return@launch
            }

            _progress.value = "Testing ${downloaded.size} models on ${audio.durationMs / 1000}s audio..."
            val allResults = mutableListOf<BenchmarkResult>()

            for ((i, model) in downloaded.withIndex()) {
                _currentModel.value = model.displayName
                _progress.value = "${i + 1}/${downloaded.size}: ${model.displayName}..."

                val result = benchmarker.benchmarkModel(model, audioUri)
                if (result != null) {
                    allResults.add(result)
                    _results.value = allResults.toList()
                }
            }

            // Reload the user's preferred model
            _progress.value = "Restoring active model..."
            val selectedId = prefsRepo.modelSize.first()
            val activeModel = ModelManager.AVAILABLE_MODELS.find { it.id == selectedId }
                ?: ModelManager.AVAILABLE_MODELS.first { it.id == "base.en" }
            try {
                for (e in engines.values) e.release()
                engines[activeModel.engine]?.loadModel(activeModel.filename)
            } catch (_: Exception) {}

            _currentModel.value = null
            _progress.value = "Done"
            _isRunning.value = false
        }
    }
}
