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
    var showAudioPicker by remember { mutableStateOf(false) }

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
            // Audio picker
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF0D0D0D))
                    .clickable { showAudioPicker = !showAudioPicker }
                    .padding(14.dp)
            ) {
                Text("Test audio", style = MaterialTheme.typography.bodySmall, color = Color(0xFF666666))
                Spacer(Modifier.height(2.dp))
                Text(
                    selectedAudio?.label ?: "No audio available",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White
                )
            }

            if (showAudioPicker) {
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

            Spacer(Modifier.height(12.dp))

            // Run button
            Button(
                onClick = { viewModel.runBenchmark() },
                enabled = !isRunning,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White,
                    contentColor = Color.Black,
                    disabledContainerColor = Color(0xFF333333),
                    disabledContentColor = Color(0xFF999999)
                )
            ) {
                if (isRunning) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color(0xFF999999), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(currentModel ?: "Running...", fontWeight = FontWeight.SemiBold)
                } else {
                    Text("Run Benchmark", fontWeight = FontWeight.SemiBold)
                }
            }

            if (progress.isNotBlank() && progress != "Done") {
                Spacer(Modifier.height(8.dp))
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
                        Text("Test all downloaded models", style = MaterialTheme.typography.titleMedium, color = Color(0xFF666666))
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Uses a recent voice note to benchmark\neach model's speed and accuracy.",
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
                    Text(result.modelName, style = MaterialTheme.typography.titleMedium, color = Color.White)
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
