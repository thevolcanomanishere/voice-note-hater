package com.watranscribe.ui.onboarding

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.watranscribe.data.repository.PreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val prefsRepo: PreferencesRepository
) : ViewModel() {

    val folderUri = prefsRepo.folderUri.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), null
    )

    fun onFolderSelected(uri: Uri) {
        viewModelScope.launch {
            prefsRepo.setFolderUri(uri.toString())
        }
    }
}
