package com.watranscribe.ui.settings

import android.app.Activity
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
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
    val folderResolutionHint by viewModel.folderResolutionHint.collectAsState()
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
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
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
                folderResolutionHint?.let { hint ->
                    Text(
                        text = hint,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF666666),
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // Model selection + download
            val downloadedModels by viewModel.downloadedModels.collectAsState()
            val downloadProgress by viewModel.downloadProgress.collectAsState()
            val storageUsed by viewModel.storageUsed.collectAsState()

            val allModels = com.watranscribe.engine.ModelManager.AVAILABLE_MODELS
            val whisperModels = allModels.filter { it.engine == com.watranscribe.engine.ENGINE_WHISPER }
            val moonshineModels = allModels.filter { it.engine == com.watranscribe.engine.ENGINE_MOONSHINE }

            var pendingDelete by remember { mutableStateOf<com.watranscribe.engine.ModelInfo?>(null) }

            @OptIn(ExperimentalFoundationApi::class)
            @Composable
            fun ModelRow(model: com.watranscribe.engine.ModelInfo) {
                val isDownloaded = model.id in downloadedModels
                val isSelected = model.id == modelSize
                val isDownloading = downloadProgress?.first == model.id
                val progress = if (isDownloading) downloadProgress?.second ?: 0f else 0f

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isSelected) Color(0xFF1A1A1A) else Color.Transparent)
                        .combinedClickable(
                            onClick = {
                                when {
                                    isDownloading -> {} // do nothing
                                    isDownloaded -> viewModel.onModelSelected(model)
                                    else -> viewModel.downloadModel(model)
                                }
                            },
                            onLongClick = {
                                if (isDownloaded && !isDownloading && !isSelected) pendingDelete = model
                            }
                        )
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

            SettingsSection("Models") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Storage used", style = MaterialTheme.typography.bodyLarge, color = Color.White)
                        Text("Long-press a downloaded model to delete", style = MaterialTheme.typography.bodySmall, color = Color(0xFF555555))
                    }
                    Text("${storageUsed / 1024 / 1024}MB", style = MaterialTheme.typography.bodyLarge, color = Color(0xFF999999))
                }
                SettingsRow(
                    label = "Benchmark models",
                    sublabel = "Compare speed and accuracy of downloaded models",
                    onClick = onBenchmark
                )
            }

            Spacer(Modifier.height(8.dp))

            SettingsSection("Whisper Models") {
                whisperModels.forEach { ModelRow(it) }
            }

            Spacer(Modifier.height(8.dp))

            SettingsSection("Moonshine Models (v2, experimental)") {
                moonshineModels.forEach { ModelRow(it) }
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

                // Battery-optimization shortcut — without this, many phones
                // (especially OnePlus/Oppo/Xiaomi) freeze our process and the
                // notification listener stops receiving WhatsApp events.
                val batteryExempt = isIgnoringBatteryOptimizations(context)
                SettingsRow(
                    label = if (batteryExempt) "Battery: unrestricted ✓" else "Allow background activity",
                    sublabel = if (batteryExempt) {
                        "Tap to change"
                    } else {
                        "Required so WhatsApp notifications can auto-trigger transcribes"
                    },
                    onClick = { openBatterySettings(context) }
                )
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

            // Bulk transcribe
            val pendingCount by viewModel.pendingCount.collectAsState()
            val failedCount by viewModel.failedCount.collectAsState()
            val completedCount by viewModel.completedCount.collectAsState()
            val pendingDurationMs by viewModel.pendingDurationMs.collectAsState()
            val failedDurationMs by viewModel.failedDurationMs.collectAsState()
            val bulkModelId by viewModel.bulkModelId.collectAsState()
            val isBulkRunning by viewModel.isBulkRunning.collectAsState()
            val bulkProgress by viewModel.bulkProgress.collectAsState()
            var showBulkModelPicker by remember { mutableStateOf(false) }
            var showClearConfirm by remember { mutableStateOf(false) }

            val downloadedModelInfos = remember(downloadedModels) {
                allModels.filter { it.id in downloadedModels }
            }
            val effectiveBulkModelId = bulkModelId ?: modelSize
            val effectiveBulkModel = remember(effectiveBulkModelId, downloadedModelInfos) {
                downloadedModelInfos.find { it.id == effectiveBulkModelId }
                    ?: downloadedModelInfos.firstOrNull()
            }
            val workCount = pendingCount + failedCount

            SettingsSection("Bulk Transcribe") {
                BulkStatRow("Pending", pendingCount, pendingDurationMs)
                BulkStatRow("Failed", failedCount, failedDurationMs)
                BulkStatRow("Completed", completedCount, durationMs = null)

                // Model picker
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = downloadedModelInfos.isNotEmpty()) {
                            showBulkModelPicker = !showBulkModelPicker
                        }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Model for this run", style = MaterialTheme.typography.bodySmall, color = Color(0xFF666666))
                        Text(
                            text = effectiveBulkModel?.displayName ?: "No models downloaded",
                            style = MaterialTheme.typography.bodyLarge,
                            color = Color.White,
                        )
                    }
                    if (downloadedModelInfos.isNotEmpty()) {
                        Text(
                            if (showBulkModelPicker) "Hide" else "Pick",
                            style = MaterialTheme.typography.labelMedium,
                            color = Color(0xFF999999),
                        )
                    }
                }

                if (showBulkModelPicker && downloadedModelInfos.isNotEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF111111))
                    ) {
                        downloadedModelInfos.forEach { model ->
                            val isSelected = model.id == effectiveBulkModelId
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        viewModel.selectBulkModel(model.id)
                                        showBulkModelPicker = false
                                    }
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(14.dp)
                                        .clip(CircleShape)
                                        .background(if (isSelected) Color.White else Color(0xFF333333))
                                )
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    text = model.displayName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (isSelected) Color.White else Color(0xFFAAAAAA),
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = "${model.sizeMb}MB · ${model.engine}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFF555555)
                                )
                            }
                        }
                    }
                }

                // Action area
                if (isBulkRunning) {
                    var liveTextExpanded by remember { mutableStateOf(false) }
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        val p = bulkProgress
                        if (p != null) {
                            // Wall-clock ticker for per-file elapsed time — updates every second
                            // in the UI without needing the worker to emit progress.
                            var elapsedSec by remember(p.fileStartedAtMs) { mutableLongStateOf(0L) }
                            LaunchedEffect(p.fileStartedAtMs) {
                                while (true) {
                                    elapsedSec = (System.currentTimeMillis() - p.fileStartedAtMs) / 1000
                                    delay(1000)
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    "${p.current} / ${p.total}",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = Color.White,
                                )
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    // Elapsed time on current file — key liveness signal
                                    Text(
                                        text = "${elapsedSec}s",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = Color(0xFF666666),
                                    )
                                    if (p.rtf != null) {
                                        Text(
                                            text = "%.2fx".format(p.rtf),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = when {
                                                p.rtf < 0.5 -> Color(0xFF4CAF50)
                                                p.rtf < 1.5 -> Color(0xFF999999)
                                                else -> Color(0xFFFF9800)
                                            },
                                        )
                                    }
                                    if (p.cpuMaxMhz > 0) {
                                        Text(
                                            text = "${p.cpuMaxMhz}MHz",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = Color(0xFF555555),
                                        )
                                    }
                                    if (p.batteryTempC > 0f) {
                                        Text(
                                            text = "%.1f°C".format(p.batteryTempC),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = when {
                                                p.batteryTempC >= 45f -> Color(0xFFFF4500)
                                                p.batteryTempC >= 40f -> Color(0xFFFF8C00)
                                                else -> Color(0xFF555555)
                                            },
                                        )
                                    }
                                    if (p.thermalStatus > 0) {
                                        val (label, color) = when (p.thermalStatus) {
                                            1 -> "WARM" to Color(0xFFFFD700)
                                            2 -> "HOT" to Color(0xFFFF8C00)
                                            else -> "THROTTLED" to Color(0xFFFF4500)
                                        }
                                        Text(
                                            text = label,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Color.Black,
                                            modifier = Modifier
                                                .clip(androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                                                .background(color)
                                                .padding(horizontal = 5.dp, vertical = 2.dp),
                                        )
                                    }
                                }
                            }
                            Text(
                                p.contact.ifBlank { p.filename },
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF999999),
                                maxLines = 1,
                            )

                            // Live transcription text — shows what's being transcribed right now
                            if (p.liveText.isNotBlank()) {
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    text = p.liveText,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFF555555),
                                    maxLines = if (liveTextExpanded) Int.MAX_VALUE else 2,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { liveTextExpanded = !liveTextExpanded },
                                )
                            }

                            Spacer(Modifier.height(8.dp))
                            LinearProgressIndicator(
                                progress = { p.current.toFloat() / p.total.coerceAtLeast(1) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(3.dp)
                                    .clip(RoundedCornerShape(2.dp)),
                                color = Color.White,
                                trackColor = Color(0xFF333333),
                            )
                            Spacer(Modifier.height(4.dp))
                            if (p.totalAudioMs > 0) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(
                                        text = "${formatBulkDuration(p.processedAudioMs)} / ${formatBulkDuration(p.totalAudioMs)} audio",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color(0xFF666666),
                                    )
                                    Text(
                                        text = formatEta(p.etaMs, p.finishAtEpochMs),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color(0xFF999999),
                                    )
                                }
                            } else {
                                Text(
                                    text = formatEta(p.etaMs, p.finishAtEpochMs),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFF999999),
                                )
                            }
                        } else {
                            Text("Starting…", style = MaterialTheme.typography.bodyLarge, color = Color.White)
                        }
                        Spacer(Modifier.height(10.dp))
                        BulkActionButton(
                            label = "Stop",
                            destructive = true,
                            enabled = true,
                            onClick = { viewModel.stopBulk() },
                        )
                    }
                } else {
                    Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        BulkActionButton(
                            label = if (workCount > 0) "Start Bulk Transcribe ($workCount)" else "Nothing to transcribe",
                            destructive = false,
                            enabled = workCount > 0 && effectiveBulkModel != null,
                            onClick = { viewModel.startBulk() },
                        )
                    }
                }

                SettingsRow(
                    label = "Clear All Transcriptions",
                    sublabel = "Reset every row to PENDING (preserves contact, duration, file links)",
                    onClick = { showClearConfirm = true }
                )
            }

            if (showClearConfirm) {
                val totalRows = pendingCount + failedCount + completedCount
                AlertDialog(
                    onDismissRequest = { showClearConfirm = false },
                    containerColor = Color(0xFF111111),
                    titleContentColor = Color.White,
                    textContentColor = Color(0xFFBBBBBB),
                    title = { Text("Clear all transcriptions?") },
                    text = {
                        Text(
                            "Resets $totalRows row${if (totalRows == 1) "" else "s"} to PENDING. " +
                                "Contact names, durations, and file links are preserved. " +
                                "Run Bulk Transcribe afterwards to re-process them."
                        )
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                viewModel.clearAllTranscriptions()
                                showClearConfirm = false
                            },
                            colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFFF6B6B))
                        ) { Text("Clear") }
                    },
                    dismissButton = {
                        TextButton(
                            onClick = { showClearConfirm = false },
                            colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFF999999))
                        ) { Text("Cancel") }
                    }
                )
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

            pendingDelete?.let { target ->
                AlertDialog(
                    onDismissRequest = { pendingDelete = null },
                    containerColor = Color(0xFF111111),
                    titleContentColor = Color.White,
                    textContentColor = Color(0xFFBBBBBB),
                    title = { Text("Delete ${target.displayName}?") },
                    text = {
                        Text("Frees ${target.sizeMb}MB. You can re-download anytime.")
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                viewModel.deleteModel(target)
                                pendingDelete = null
                            },
                            colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFFF6B6B))
                        ) { Text("Delete") }
                    },
                    dismissButton = {
                        TextButton(
                            onClick = { pendingDelete = null },
                            colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFF999999))
                        ) { Text("Cancel") }
                    }
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

@Composable
private fun BulkStatRow(label: String, count: Int, durationMs: Long?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = Color(0xFFAAAAAA))
        val durStr = durationMs?.takeIf { it > 0 }?.let { " · ${formatBulkDuration(it)} audio" } ?: ""
        Text("$count$durStr", style = MaterialTheme.typography.bodyLarge, color = Color.White)
    }
}

@Composable
private fun BulkActionButton(
    label: String,
    destructive: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val bg = when {
        !enabled -> Color(0xFF1A1A1A)
        destructive -> Color(0xFF2A1010)
        else -> Color.White
    }
    val fg = when {
        !enabled -> Color(0xFF666666)
        destructive -> Color(0xFFFF6B6B)
        else -> Color.Black
    }
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        color = fg,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 12.dp),
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
    )
}

private fun formatBulkDuration(ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return when {
        h > 0 -> "${h}h ${m}m"
        m > 0 -> "${m}m"
        else -> "${s}s"
    }
}

private fun formatEta(etaMs: Long?, finishAtEpochMs: Long?): String {
    if (etaMs == null || finishAtEpochMs == null) return "ETA: …"
    val finishStr = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        .format(java.util.Date(finishAtEpochMs))
    return "~${formatBulkDuration(etaMs)} · $finishStr"
}

private fun isNotificationListenerEnabled(context: android.content.Context): Boolean {
    return com.watranscribe.engine.WhatsAppNotificationListener.isEnabled(context)
}

private fun isIgnoringBatteryOptimizations(context: android.content.Context): Boolean {
    val pm = context.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
    return pm.isIgnoringBatteryOptimizations(context.packageName)
}

/**
 * Opens the system "battery optimization" screen so the user can whitelist us.
 * Stock Android honours ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS as a direct
 * per-app prompt; OEMs (Oppo / OnePlus / Xiaomi / etc.) often silently reject
 * that intent or lack the screen entirely, so we progressively fall back to the
 * generic ignore-list, then to the app's info page where the user can navigate
 * to Battery / Autostart manually.
 */
private fun openBatterySettings(context: android.content.Context) {
    val pkg = context.packageName
    val candidates = listOf(
        android.content.Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = android.net.Uri.parse("package:$pkg")
            flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
        },
        android.content.Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
            flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
        },
        android.content.Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = android.net.Uri.parse("package:$pkg")
            flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
        },
    )
    for (intent in candidates) {
        try {
            context.startActivity(intent)
            return
        } catch (_: android.content.ActivityNotFoundException) {
            // try next candidate
        }
    }
}
