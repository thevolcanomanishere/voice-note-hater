package com.watranscribe.ui.benchmark

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.watranscribe.engine.BenchmarkResult

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BenchmarkScreen(
    onBack: () -> Unit,
    viewModel: BenchmarkViewModel = hiltViewModel()
) {
    val results by viewModel.results.collectAsState()
    val isRunning by viewModel.isRunning.collectAsState()
    val currentModel by viewModel.currentModel.collectAsState()
    val progress by viewModel.progress.collectAsState()
    val audioChoices by viewModel.audioChoices.collectAsState()
    val selectedAudio by viewModel.selectedAudio.collectAsState()
    val downloadedModels by viewModel.downloadedModels.collectAsState()
    val selectedModelIds by viewModel.selectedModelIds.collectAsState()
    var showAudioPicker by remember { mutableStateOf(false) }
    var showModelPicker by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        TopAppBar(
            title = { Text("Benchmark", style = MaterialTheme.typography.headlineMedium, color = Color.White) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black)
        )

        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            // Audio picker row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF0D0D0D))
                    .padding(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { showAudioPicker = !showAudioPicker }
                        .padding(10.dp)
                ) {
                    Text("Test audio", style = MaterialTheme.typography.bodySmall, color = Color(0xFF666666))
                    Text(
                        selectedAudio?.label ?: "No audio available",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White,
                        maxLines = 1
                    )
                }
                // Run button inline
                val canRun = !isRunning && selectedModelIds.isNotEmpty() && selectedAudio != null
                Text(
                    text = when {
                        isRunning -> currentModel ?: "..."
                        selectedModelIds.isEmpty() -> "Pick models"
                        else -> "Run"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = if (canRun) Color.Black else Color(0xFF666666),
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (canRun) Color.White else Color(0xFF1A1A1A))
                        .clickable(enabled = canRun) { viewModel.runBenchmark() }
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                )
            }

            if (showAudioPicker) {
                Spacer(Modifier.height(2.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF111111))
                        .padding(4.dp)
                ) {
                    audioChoices.forEach { choice ->
                        val isSelected = choice.uri == selectedAudio?.uri
                        Text(
                            text = choice.label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (isSelected) Color.White else Color(0xFF888888),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.selectAudio(choice)
                                    showAudioPicker = false
                                }
                                .padding(horizontal = 12.dp, vertical = 10.dp)
                        )
                    }
                }
            }

            // Model picker summary row
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF0D0D0D))
                    .clickable { showModelPicker = !showModelPicker }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Models", style = MaterialTheme.typography.bodySmall, color = Color(0xFF666666))
                    val summary = when {
                        downloadedModels.isEmpty() -> "No models downloaded"
                        selectedModelIds.isEmpty() -> "None selected"
                        selectedModelIds.size == downloadedModels.size -> "All ${downloadedModels.size} models"
                        else -> "${selectedModelIds.size} of ${downloadedModels.size} models"
                    }
                    Text(summary, style = MaterialTheme.typography.bodyMedium, color = Color.White)
                }
                Text(
                    if (showModelPicker) "Hide" else "Pick",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color(0xFF999999)
                )
            }

            if (showModelPicker) {
                Spacer(Modifier.height(2.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF111111))
                        .padding(4.dp)
                ) {
                    // All/None toggles
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(
                            "All",
                            style = MaterialTheme.typography.labelMedium,
                            color = Color(0xFF999999),
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .clickable { viewModel.selectAllModels() }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                        Text(
                            "None",
                            style = MaterialTheme.typography.labelMedium,
                            color = Color(0xFF999999),
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .clickable { viewModel.clearModelSelection() }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                    downloadedModels.forEach { model ->
                        val isChecked = model.id in selectedModelIds
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.toggleModel(model.id) }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(16.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(if (isChecked) Color.White else Color(0xFF333333))
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                text = model.displayName,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (isChecked) Color.White else Color(0xFF888888),
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = model.engine,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (model.engine == "moonshine") Color(0xFF7C5BF2) else Color(0xFF3B82A8)
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = "${model.sizeMb}MB",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF555555)
                            )
                        }
                    }
                }
            }

            if (isRunning && progress.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(progress, style = MaterialTheme.typography.bodySmall, color = Color(0xFF666666))
            }

            Spacer(Modifier.height(12.dp))

            if (results.isEmpty() && !isRunning) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 48.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Compare all downloaded models", style = MaterialTheme.typography.titleMedium, color = Color(0xFF666666))
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Pick a voice note above, then tap Run.\nEach model transcribes the same audio.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF444444),
                            lineHeight = 18.sp
                        )
                    }
                }
            }

            // Results
            if (results.isNotEmpty()) {
                // Find best for highlighting
                val fastestMs = results.minOf { it.inferenceMs }

                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    // Header
                    item {
                        Text(
                            "Results · ${results.first().audioSeconds.let { "%.1fs".format(it) }} audio",
                            style = MaterialTheme.typography.labelMedium,
                            color = Color(0xFF666666),
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }

                    itemsIndexed(results.sortedBy { it.inferenceMs }) { index, result ->
                        ResultCard(
                            result = result,
                            rank = index + 1,
                            isFastest = result.inferenceMs == fastestMs
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultCard(result: BenchmarkResult, rank: Int, isFastest: Boolean) {
    var expanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (isFastest) Color(0xFF111111) else Color(0xFF0D0D0D))
            .clickable { expanded = !expanded }
            .padding(14.dp)
            .animateContentSize()
    ) {
        // Title row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "#$rank",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isFastest) Color(0xFF4CAF50) else Color(0xFF555555),
                    modifier = Modifier.width(28.dp)
                )
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(result.modelName, style = MaterialTheme.typography.titleMedium, color = Color.White)
                        Spacer(Modifier.width(6.dp))
                        EngineBadge(result.engineId)
                    }
                    Text("${result.modelSizeMb}MB", style = MaterialTheme.typography.bodySmall, color = Color(0xFF555555))
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "%.1fs".format(result.inferenceMs / 1000.0),
                    style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
                    color = if (isFastest) Color(0xFF4CAF50) else Color.White
                )
                val speedMultiplier = if (result.rtf > 0) 1.0 / result.rtf else 0.0
                Text(
                    "%.0fx speed".format(speedMultiplier),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = Color(0xFF666666)
                )
            }
        }

        // Timing breakdown
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            TimingChip("load", result.loadMs)
            TimingChip("decode", result.decodeMs)
            TimingChip("infer", result.inferenceMs)
            TimingChip("total", result.totalMs)
        }

        // Speed bar
        Spacer(Modifier.height(8.dp))
        val barFraction = (1.0 / result.rtf.coerceAtLeast(0.01)).toFloat().coerceIn(0f, 1f)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Color(0xFF1A1A1A))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(barFraction)
                    .height(4.dp)
                    .background(if (isFastest) Color(0xFF4CAF50) else Color(0xFF444444))
            )
        }

        // Transcript preview (expanded)
        if (expanded) {
            Spacer(Modifier.height(10.dp))
            Text(
                result.transcript,
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFFBBBBBB),
                maxLines = 10,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun EngineBadge(engineId: String) {
    val (label, color) = when (engineId) {
        "moonshine" -> "moonshine" to Color(0xFF7C5BF2)
        "whisper" -> "whisper" to Color(0xFF3B82A8)
        else -> engineId to Color(0xFF555555)
    }
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold),
        color = Color.White,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color)
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

@Composable
private fun TimingChip(label: String, ms: Long) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            formatMs(ms),
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = Color(0xFFAAAAAA)
        )
        Text(label, style = MaterialTheme.typography.bodySmall, color = Color(0xFF555555))
    }
}

private fun formatMs(ms: Long): String = when {
    ms < 1000 -> "${ms}ms"
    else -> "%.1fs".format(ms / 1000.0)
}
