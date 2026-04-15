package com.watranscribe.ui.settings

import android.app.Activity
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onBenchmark: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val folderUri by viewModel.folderUri.collectAsState()
    val modelSize by viewModel.modelSize.collectAsState()
    val backgroundScan by viewModel.backgroundScanEnabled.collectAsState()
    val transcriptionCount by viewModel.transcriptionCount.collectAsState()

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                context.contentResolver.takePersistableUriPermission(uri, flags)
                viewModel.onFolderSelected(uri)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        TopAppBar(
            title = {
                Text("Settings", style = MaterialTheme.typography.headlineMedium, color = Color.White)
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black)
        )

        Column(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // Notification access for contact detection
            val notifEnabled = isNotificationListenerEnabled(context)
            if (!notifEnabled) {
                SettingsSection("Contact Detection") {
                    SettingsRow(
                        label = "Enable notification access",
                        sublabel = "Required to identify who sent voice notes",
                        onClick = {
                            context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                        }
                    )
                }
                Spacer(Modifier.height(8.dp))
            }

            // Watched folder
            SettingsSection("Watched Folder") {
                SettingsRow(
                    label = folderUri?.let {
                        java.net.URLDecoder.decode(it, "UTF-8")
                            .substringAfterLast("/")
                            .substringAfterLast(":")
                    } ?: "Not set",
                    sublabel = "Tap to change",
                    onClick = {
                        folderPickerLauncher.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE))
                    }
                )
            }

            Spacer(Modifier.height(8.dp))

            // Model selection + download
            val downloadedModels by viewModel.downloadedModels.collectAsState()
            val downloadProgress by viewModel.downloadProgress.collectAsState()
            val storageUsed by viewModel.storageUsed.collectAsState()

            SettingsSection("Whisper Model") {
                com.watranscribe.engine.ModelManager.AVAILABLE_MODELS.forEach { model ->
                    val isDownloaded = model.id in downloadedModels
                    val isSelected = model.id == modelSize
                    val isDownloading = downloadProgress?.first == model.id
                    val progress = if (isDownloading) downloadProgress?.second ?: 0f else 0f

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isSelected) Color(0xFF1A1A1A) else Color.Transparent)
                            .clickable {
                                when {
                                    isDownloading -> {} // do nothing
                                    isDownloaded -> viewModel.onModelSelected(model)
                                    else -> viewModel.downloadModel(model)
                                }
                            }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = model.displayName,
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (isSelected) Color.White else if (isDownloaded) Color(0xFFCCCCCC) else Color(0xFF666666)
                            )
                            Text(
                                text = "${model.sizeMb}MB · ${model.description}",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF444444)
                            )
                            if (isDownloading) {
                                Spacer(Modifier.height(4.dp))
                                LinearProgressIndicator(
                                    progress = { progress },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(2.dp)
                                        .clip(RoundedCornerShape(1.dp)),
                                    color = Color.White,
                                    trackColor = Color(0xFF333333),
                                )
                            }
                        }
                        when {
                            isSelected -> Text("Active", style = MaterialTheme.typography.labelMedium, color = Color(0xFF4CAF50))
                            isDownloading -> Text("${(progress * 100).toInt()}%", style = MaterialTheme.typography.labelMedium, color = Color(0xFF999999))
                            isDownloaded -> Text("Downloaded", style = MaterialTheme.typography.labelMedium, color = Color(0xFF666666))
                            else -> Text("Download", style = MaterialTheme.typography.labelMedium, color = Color.White)
                        }
                    }
                }
                // Storage used
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Models storage", style = MaterialTheme.typography.bodySmall, color = Color(0xFF444444))
                    Text("${storageUsed / 1024 / 1024}MB", style = MaterialTheme.typography.bodySmall, color = Color(0xFF444444))
                }
                // Benchmark button
                SettingsRow(
                    label = "Benchmark models",
                    sublabel = "Compare speed and accuracy of downloaded models",
                    onClick = onBenchmark
                )
            }

            Spacer(Modifier.height(8.dp))

            // Background scanning
            SettingsSection("Background") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Auto-scan", style = MaterialTheme.typography.bodyLarge, color = Color.White)
                        Text(
                            "Check for new voice notes every 15 min",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF666666)
                        )
                    }
                    Switch(
                        checked = backgroundScan,
                        onCheckedChange = { viewModel.onBackgroundScanToggled(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = Color(0xFF333333),
                            uncheckedThumbColor = Color(0xFF666666),
                            uncheckedTrackColor = Color(0xFF1A1A1A)
                        )
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // Storage info
            SettingsSection("Storage") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Transcriptions stored", style = MaterialTheme.typography.bodyLarge, color = Color(0xFF999999))
                    Text("$transcriptionCount", style = MaterialTheme.typography.bodyLarge, color = Color.White)
                }
            }

            Spacer(Modifier.height(8.dp))

            // Test mode
            SettingsSection("Test") {
                SettingsRow(
                    label = "Short notification",
                    sublabel = "Simulate a quick transcription result",
                    onClick = { viewModel.testNotification("short") }
                )
                SettingsRow(
                    label = "Long notification",
                    sublabel = "Simulate a long transcription with streaming",
                    onClick = { viewModel.testNotification("long") }
                )
                SettingsRow(
                    label = "Progress notification",
                    sublabel = "Simulate live transcription progress",
                    onClick = { viewModel.testNotification("progress") }
                )
            }
        }
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Column {
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = Color(0xFF666666),
            modifier = Modifier.padding(vertical = 8.dp)
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF0D0D0D))
        ) {
            content()
        }
    }
}

@Composable
private fun SettingsRow(label: String, sublabel: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyLarge, color = Color.White)
        Text(text = sublabel, style = MaterialTheme.typography.bodySmall, color = Color(0xFF666666))
    }
}

private fun isNotificationListenerEnabled(context: android.content.Context): Boolean {
    return com.watranscribe.engine.WhatsAppNotificationListener.isEnabled(context)
}
