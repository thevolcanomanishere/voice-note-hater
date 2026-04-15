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
import com.watranscribe.engine.WhisperEngine
import com.watranscribe.data.repository.PreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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
    private val whisperEngine: WhisperEngine,
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

    init {
        viewModelScope.launch { loadAudioChoices() }
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
            val downloaded = modelManager.getDownloadedModels()
            if (downloaded.isEmpty()) {
                _progress.value = "No models downloaded."
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
            val activeFilename = ModelManager.AVAILABLE_MODELS
                .find { it.id == selectedId }?.filename ?: "ggml-base.en.bin"
            try {
                whisperEngine.release()
                whisperEngine.loadModel(activeFilename)
            } catch (_: Exception) {}

            _currentModel.value = null
            _progress.value = "Done"
            _isRunning.value = false
        }
    }
}
