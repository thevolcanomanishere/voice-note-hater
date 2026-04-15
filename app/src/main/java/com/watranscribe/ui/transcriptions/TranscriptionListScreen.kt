package com.watranscribe.ui.transcriptions

import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.watranscribe.data.local.TimedSegment
import com.watranscribe.data.local.TranscriptionEntity
import com.watranscribe.data.local.TranscriptionStatus
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranscriptionListScreen(
    onSettingsClick: () -> Unit,
    viewModel: TranscriptionListViewModel = hiltViewModel()
) {
    val transcriptions by viewModel.transcriptions.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val isScanning by viewModel.isScanning.collectAsState()
    val transcribingId by viewModel.transcribingId.collectAsState()
    val liveText by viewModel.liveText.collectAsState()

    var playingId by remember { mutableStateOf<Long?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        TopAppBar(
            title = {
                Text(
                    "Voice Note Hater",
                    style = MaterialTheme.typography.headlineMedium,
                    color = Color.White
                )
            },
            actions = {
                if (isScanning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(8.dp))
                } else {
                    IconButton(onClick = { viewModel.scanNow() }) {
                        Icon(Icons.Default.Refresh, "Scan", tint = Color.White)
                    }
                }
                IconButton(onClick = onSettingsClick) {
                    Icon(Icons.Default.Settings, "Settings", tint = Color.White)
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black)
        )

        TextField(
            value = searchQuery,
            onValueChange = { viewModel.onSearchQueryChanged(it) },
            placeholder = {
                Text("Search transcriptions...", color = Color(0xFF666666))
            },
            leadingIcon = {
                Icon(Icons.Default.Search, null, tint = Color(0xFF666666))
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .clip(RoundedCornerShape(12.dp)),
            colors = TextFieldDefaults.colors(
                unfocusedContainerColor = Color(0xFF0D0D0D),
                focusedContainerColor = Color(0xFF1A1A1A),
                unfocusedTextColor = Color.White,
                focusedTextColor = Color.White,
                cursorColor = Color.White,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent
            ),
            singleLine = true
        )

        // Notification access banner
        val context = LocalContext.current
        val notifEnabled = remember {
            com.watranscribe.engine.WhatsAppNotificationListener.isEnabled(context)
        }
        var bannerDismissed by remember { mutableStateOf(false) }
        if (!notifEnabled && !bannerDismissed) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF1A1A1A))
                    .clickable {
                        context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    }
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Enable notification access", style = MaterialTheme.typography.bodyMedium, color = Color.White)
                    Text("Identifies who sent each voice note", style = MaterialTheme.typography.bodySmall, color = Color(0xFF666666))
                }
                Text(
                    "x",
                    color = Color(0xFF666666),
                    modifier = Modifier
                        .clickable { bannerDismissed = true }
                        .padding(8.dp)
                )
            }
        }

        if (transcriptions.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("No transcriptions yet", style = MaterialTheme.typography.titleMedium, color = Color(0xFF666666))
                    Spacer(Modifier.height(8.dp))
                    Text("Tap the refresh icon to scan for voice notes", style = MaterialTheme.typography.bodySmall, color = Color(0xFF444444))
                }
            }
        } else {
            val grouped = transcriptions.groupBy { it.conversationFolder }
            val listState = rememberLazyListState()

            // Auto-scroll to actively transcribing item
            LaunchedEffect(transcribingId) {
                if (transcribingId != null) {
                    var idx = 0
                    for ((_, items) in grouped) {
                        idx++ // header
                        for (item in items) {
                            if (item.id == transcribingId) {
                                listState.animateScrollToItem(idx)
                                return@LaunchedEffect
                            }
                            idx++
                        }
                    }
                }
            }

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                grouped.forEach { (folder, items) ->
                    item(key = "header_$folder") {
                        Text(
                            text = folder,
                            style = MaterialTheme.typography.labelMedium,
                            color = Color(0xFF666666),
                            modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
                        )
                    }
                    items(items, key = { it.id }) { transcription ->
                        TranscriptionCard(
                            entity = transcription,
                            isTranscribing = transcription.id == transcribingId,
                            liveText = if (transcription.id == transcribingId) liveText else null,
                            isPlaying = transcription.id == playingId,
                            onTranscribeClick = { viewModel.transcribeItem(transcription) },
                            onRetranscribe = { model -> viewModel.retranscribeWith(transcription, model) },
                            downloadedModels = { viewModel.getDownloadedModels() },
                            onPlayingChanged = { playing ->
                                playingId = if (playing) transcription.id else null
                            }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TranscriptionCard(
    entity: TranscriptionEntity,
    isTranscribing: Boolean,
    liveText: String?,
    isPlaying: Boolean,
    onTranscribeClick: () -> Unit,
    onRetranscribe: (com.watranscribe.engine.ModelInfo) -> Unit = {},
    downloadedModels: () -> List<com.watranscribe.engine.ModelInfo> = { emptyList() },
    onPlayingChanged: (Boolean) -> Unit
) {
    var manualExpanded by remember { mutableStateOf(false) }
    // Auto-expand during transcription, and keep open after it finishes
    if (isTranscribing) {
        manualExpanded = true
    }
    val expanded = manualExpanded
    val dateFormat = remember { SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()) }
    val canTranscribe = entity.status == TranscriptionStatus.PENDING || entity.status == TranscriptionStatus.FAILED
    val displayText = when {
        isTranscribing && !liveText.isNullOrBlank() -> liveText
        entity.transcription != null -> entity.transcription
        else -> null
    }
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

    // Parse timed segments
    val segments = remember(entity.segmentsJson) {
        if (entity.segmentsJson.isBlank()) emptyList()
        else entity.segmentsJson.lines().filter { it.isNotBlank() }.mapNotNull { line ->
            val parts = line.split("|", limit = 3)
            if (parts.size == 3) TimedSegment(parts[2], parts[0].toLongOrNull() ?: 0, parts[1].toLongOrNull() ?: 0)
            else null
        }
    }

    // Track playback position in ms for highlighting
    var playbackMs by remember { mutableIntStateOf(0) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (isTranscribing) Color(0xFF111111) else Color(0xFF0D0D0D))
            .combinedClickable(
                onClick = {
                    if (canTranscribe) {
                        onTranscribeClick()
                    } else {
                        manualExpanded = !manualExpanded
                    }
                },
                onLongClick = {
                    if (displayText != null) {
                        clipboardManager.setText(AnnotatedString(displayText))
                        Toast
                            .makeText(context, "Copied", Toast.LENGTH_SHORT)
                            .show()
                    }
                }
            )
            .padding(16.dp)
            .animateContentSize()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entity.contact.ifBlank { entity.filename },
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Row {
                    Text(
                        text = dateFormat.format(Date(entity.lastModified)),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF666666)
                    )
                    if (entity.durationMs > 0) {
                        Text(
                            text = "  ·  ${formatDuration(entity.durationMs)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF666666)
                        )
                    }
                    if (entity.contact.isNotBlank()) {
                        Text(
                            text = "  ·  ${entity.filename}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF444444),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            if (isTranscribing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    color = Color.White,
                    strokeWidth = 1.5.dp
                )
            } else {
                StatusIndicator(entity.status)
            }
        }

        if (displayText != null) {
            Spacer(Modifier.height(8.dp))

            if (isPlaying && segments.isNotEmpty() && (expanded || isTranscribing)) {
                // Timed highlighting during playback
                TimedText(segments = segments, currentMs = playbackMs)
            } else {
                Text(
                    text = displayText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isTranscribing) Color(0xFFBBBBBB) else Color(0xFFE5E5E5),
                    maxLines = if (expanded || isTranscribing) Int.MAX_VALUE else 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        } else if (canTranscribe && !isTranscribing) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Tap to transcribe",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF444444)
            )
        }

        // Audio player + retranscribe when expanded
        if (expanded && entity.status == TranscriptionStatus.COMPLETED) {
            Spacer(Modifier.height(12.dp))
            AudioPlayer(
                uri = entity.uri,
                isPlaying = isPlaying,
                onPlayingChanged = onPlayingChanged,
                onPositionUpdate = { playbackMs = it }
            )

            // Retranscribe with different model
            var showModels by remember { mutableStateOf(false) }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Show which model was used
                if (entity.modelUsed.isNotBlank()) {
                    Text(
                        text = entity.modelUsed,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF444444)
                    )
                } else {
                    Spacer(Modifier.width(1.dp))
                }
                Text(
                    text = if (showModels) "Cancel" else "Retranscribe",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF555555),
                    modifier = Modifier.clickable { showModels = !showModels }
                )
            }
            if (showModels) {
                Spacer(Modifier.height(6.dp))
                val models = remember { downloadedModels() }
                @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    models.forEach { model ->
                        Text(
                            text = model.displayName.replace(" (English)", ""),
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(0xFF1A1A1A))
                                .clickable {
                                    showModels = false
                                    onRetranscribe(model)
                                }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TimedText(segments: List<TimedSegment>, currentMs: Int) {
    val annotated = buildAnnotatedString {
        for (segment in segments) {
            val fraction = if (currentMs >= segment.endMs) 1f
            else if (currentMs <= segment.startMs) 0f
            else (currentMs - segment.startMs).toFloat() / (segment.endMs - segment.startMs).coerceAtLeast(1)

            // Interpolate color from grey to white based on playback position
            val grey = Color(0xFF555555)
            val white = Color(0xFFE5E5E5)
            val color = lerp(grey, white, fraction.coerceIn(0f, 1f))

            withStyle(SpanStyle(color = color)) {
                append(segment.text)
            }
        }
    }
    Text(text = annotated, style = MaterialTheme.typography.bodyMedium)
}

private fun lerp(a: Color, b: Color, t: Float): Color {
    return Color(
        red = a.red + (b.red - a.red) * t,
        green = a.green + (b.green - a.green) * t,
        blue = a.blue + (b.blue - a.blue) * t,
        alpha = 1f
    )
}

@Composable
private fun AudioPlayer(
    uri: String,
    isPlaying: Boolean,
    onPlayingChanged: (Boolean) -> Unit,
    onPositionUpdate: (Int) -> Unit
) {
    val context = LocalContext.current
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var progress by remember { mutableFloatStateOf(0f) }
    var durationMs by remember { mutableIntStateOf(0) }
    var isSeeking by remember { mutableStateOf(false) }

    DisposableEffect(uri) {
        onDispose {
            player?.release()
            player = null
            onPlayingChanged(false)
        }
    }

    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            if (!isSeeking) {
                player?.let { mp ->
                    if (mp.isPlaying) {
                        val pos = mp.currentPosition
                        durationMs = mp.duration.coerceAtLeast(1)
                        progress = pos.toFloat() / durationMs
                        onPositionUpdate(pos)
                    }
                }
            }
            delay(50)
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = {
                if (isPlaying) {
                    player?.stop()
                    player?.release()
                    player = null
                    progress = 0f
                    durationMs = 0
                    onPositionUpdate(0)
                    onPlayingChanged(false)
                } else {
                    player?.release()
                    player = MediaPlayer().apply {
                        setDataSource(context, Uri.parse(uri))
                        prepare()
                        durationMs = duration
                        setOnCompletionListener {
                            progress = 1f
                            onPositionUpdate(0)
                            onPlayingChanged(false)
                        }
                        start()
                    }
                    onPlayingChanged(true)
                }
            },
            modifier = Modifier.size(36.dp)
        ) {
            Icon(
                if (isPlaying) Icons.Default.Stop else Icons.Default.PlayArrow,
                contentDescription = if (isPlaying) "Stop" else "Play",
                tint = Color.White,
                modifier = Modifier.size(20.dp)
            )
        }

        Spacer(Modifier.width(8.dp))

        Column(modifier = Modifier.weight(1f)) {
            androidx.compose.material3.Slider(
                value = progress,
                onValueChange = { value ->
                    isSeeking = true
                    progress = value
                },
                onValueChangeFinished = {
                    player?.let { mp ->
                        val seekTo = (progress * mp.duration).toInt()
                        mp.seekTo(seekTo)
                        onPositionUpdate(seekTo)
                    }
                    isSeeking = false
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(24.dp),
                colors = androidx.compose.material3.SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = Color.White,
                    inactiveTrackColor = Color(0xFF333333)
                )
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                val currentMs = (progress * durationMs).toLong()
                Text(formatDuration(currentMs), style = MaterialTheme.typography.bodySmall, color = Color(0xFF666666))
                Text(formatDuration(durationMs.toLong()), style = MaterialTheme.typography.bodySmall, color = Color(0xFF666666))
            }
        }
    }
}

@Composable
private fun StatusIndicator(status: TranscriptionStatus) {
    val (color, label) = when (status) {
        TranscriptionStatus.PENDING -> Color(0xFF555555) to "tap"
        TranscriptionStatus.IN_PROGRESS -> Color(0xFF999999) to "..."
        TranscriptionStatus.COMPLETED -> Color(0xFF4CAF50) to ""
        TranscriptionStatus.FAILED -> Color(0xFFCF6679) to "retry"
    }
    if (label.isNotEmpty()) {
        Text(text = label, style = MaterialTheme.typography.labelMedium, color = color)
    }
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "${minutes}:${"%02d".format(seconds)}"
}
