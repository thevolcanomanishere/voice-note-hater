package com.watranscribe.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

@Singleton
class PreferencesRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val store = context.dataStore

    companion object {
        val FOLDER_URI = stringPreferencesKey("folder_uri")
        val MODEL_SIZE = stringPreferencesKey("model_size")
        val BACKGROUND_SCAN = booleanPreferencesKey("background_scan")
    }

    val folderUri: Flow<String?> = store.data.map { it[FOLDER_URI] }
    val modelSize: Flow<String> = store.data.map { it[MODEL_SIZE] ?: "base" }
    val backgroundScanEnabled: Flow<Boolean> = store.data.map { it[BACKGROUND_SCAN] ?: true }

    suspend fun setFolderUri(uri: String) {
        store.edit { it[FOLDER_URI] = uri }
    }

    suspend fun setModelSize(size: String) {
        store.edit { it[MODEL_SIZE] = size }
    }

    suspend fun setBackgroundScan(enabled: Boolean) {
        store.edit { it[BACKGROUND_SCAN] = enabled }
    }
}
