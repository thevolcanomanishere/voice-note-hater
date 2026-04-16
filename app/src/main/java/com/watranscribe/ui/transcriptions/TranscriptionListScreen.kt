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
import androidx.compose.runtime.mutableLongStateOf
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
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.WeekFields
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
    val sortMode by viewModel.sortMode.collectAsState()

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

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SortChip(
                label = "Newest",
                selected = sortMode == ListSortMode.NEWEST,
                onClick = { viewModel.setSortMode(ListSortMode.NEWEST) },
            )
            SortChip(
                label = "Audio Length",
                selected = sortMode == ListSortMode.AUDIO_LENGTH,
                onClick = { viewModel.setSortMode(ListSortMode.AUDIO_LENGTH) },
            )
            SortChip(
                label = "Week Buckets",
                selected = sortMode == ListSortMode.WEEK_BUCKETS,
                onClick = { viewModel.setSortMode(ListSortMode.WEEK_BUCKETS) },
            )
        }

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
            val sections = remember(transcriptions, sortMode) {
                buildSections(transcriptions, sortMode)
            }
            val listState = rememberLazyListState()

            // Auto-scroll to actively transcribing item
            LaunchedEffect(transcribingId) {
                if (transcribingId != null) {
                    var idx = 0
                    for (section in sections) {
                        if (section.showHeader) idx++
                        for (item in section.items) {
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
                sections.forEach { section ->
                    if (section.showHeader) {
                        item(key = "header_${section.key}") {
                            Text(
                                text = section.title,
                                style = MaterialTheme.typography.labelMedium,
                                color = Color(0xFF666666),
                                modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
                            )
                        }
                    }
                    items(section.items, key = { it.id }) { transcription ->
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

@Composable
private fun SortChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = if (selected) Color.Black else Color(0xFF999999),
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (selected) Color.White else Color(0xFF111111))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    )
}

private data class ListSection(
    val key: String,
    val title: String,
    val showHeader: Boolean,
    val items: List<TranscriptionEntity>,
)

private fun buildSections(
    transcriptions: List<TranscriptionEntity>,
    sortMode: ListSortMode,
): List<ListSection> {
    return when (sortMode) {
        ListSortMode.NEWEST -> listOf(
            ListSection(
                key = "all_newest",
                title = "",
                showHeader = false,
                items = transcriptions.sortedByDescending { it.lastModified },
            )
        )
        ListSortMode.AUDIO_LENGTH -> listOf(
            ListSection(
                key = "all_length",
                title = "",
                showHeader = false,
                items = transcriptions.sortedWith(
                    compareByDescending<TranscriptionEntity> { it.durationMs }
                        .thenByDescending { it.lastModified }
                ),
            )
        )
        ListSortMode.WEEK_BUCKETS -> {
            val grouped = transcriptions.groupBy { it.conversationFolder }
            grouped
                .toList()
                .sortedByDescending { weekSortKey(it.first) }
                .map { (folder, items) ->
                    ListSection(
                        key = folder,
                        title = weekBucketLabel(folder),
                        showHeader = true,
                        items = items.sortedByDescending { it.lastModified },
                    )
                }
        }
    }
}

private fun weekSortKey(folder: String): Long {
    val parsed = parseYearWeek(folder) ?: return Long.MIN_VALUE
    return parsed.first * 100L + parsed.second
}

private fun weekBucketLabel(folder: String): String {
    val yearWeek = parseYearWeek(folder) ?: return folder
    val wf = WeekFields.ISO
    return try {
        val start = LocalDate.of(yearWeek.first.toInt(), 1, 4)
            .with(wf.weekOfWeekBasedYear(), yearWeek.second)
            .with(DayOfWeek.MONDAY)
        val end = start.plusDays(6)
        val shortFmt = DateTimeFormatter.ofPattern("MMM d", Locale.getDefault())
        val longFmt = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.getDefault())
        val range = if (start.year == end.year) {
            "${start.format(shortFmt)} - ${end.format(longFmt)}"
        } else {
            "${start.format(longFmt)} - ${end.format(longFmt)}"
        }
        "$range · W${yearWeek.second.toInt()}"
    } catch (_: Exception) {
        folder
    }
}

private fun parseYearWeek(folder: String): Pair<Long, Long>? {
    val compact = Regex("^(\\d{4})(\\d{2})$").matchEntire(folder)
    if (compact != null) {
        val year = compact.groupValues[1].toLongOrNull() ?: return null
        val week = compact.groupValues[2].toLongOrNull() ?: return null
        return if (week in 1..53) year to week else null
    }

    val pretty = Regex("^(\\d{4})\\s*W(\\d{1,2})$").matchEntire(folder)
    if (pretty != null) {
        val year = pretty.groupValues[1].toLongOrNull() ?: return null
        val week = pretty.groupValues[2].toLongOrNull() ?: return null
        return if (week in 1..53) year to week else null
    }
    return null
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

    // Parse timed segments (JSON for new records, legacy pipe format for old whisper rows)
    val segments = remember(entity.segmentsJson) {
        com.watranscribe.data.local.SegmentsCodec.parse(entity.segmentsJson)
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
                knownDurationMs = entity.durationMs,
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

// Small offset to cover MediaPlayer's startup lag (typically 30-60ms between
// mp.start() returning and actual audio output). Real word timings from
// Moonshine's streaming API are tight — only audio-pipeline latency remains.
private const val WORD_HIGHLIGHT_LAG_MS = 40

@Composable
private fun TimedText(segments: List<TimedSegment>, currentMs: Int) {
    val grey = Color(0xFF555555)
    val activeWhite = Color.White
    val effectiveMs = currentMs - WORD_HIGHLIGHT_LAG_MS

    val annotated = buildAnnotatedString {
        for ((segIndex, segment) in segments.withIndex()) {
            if (segment.words.isNotEmpty()) {
                // Karaoke-style word highlighting: each word pops to white the
                // moment playback reaches it (+ small lag for audio sync).
                for ((wIdx, w) in segment.words.withIndex()) {
                    val color = if (effectiveMs >= w.startMs) activeWhite else grey
                    withStyle(SpanStyle(color = color)) { append(w.text) }
                    if (wIdx != segment.words.lastIndex) {
                        withStyle(SpanStyle(color = color)) { append(' ') }
                    }
                }
            } else {
                // Whisper records already expose per-token segments — treat each
                // segment as a pop-in unit (same behaviour as word-level above).
                val color = if (effectiveMs >= segment.startMs) activeWhite else grey
                withStyle(SpanStyle(color = color)) { append(segment.text) }
            }
            // Space between segments
            if (segIndex != segments.lastIndex) append(' ')
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
    knownDurationMs: Long,
    isPlaying: Boolean,
    onPlayingChanged: (Boolean) -> Unit,
    onPositionUpdate: (Int) -> Unit
) {
    val context = LocalContext.current
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var progress by remember { mutableFloatStateOf(0f) }
    // MediaPlayer.duration AND MediaPlayer.currentPosition are both unreliable for
    // .opus files (currentPosition often jumps ahead of real audio). We drive the
    // progress bar off wall-clock time instead: audio plays at 1x, so elapsed
    // wall-clock since play/seek start = audio position. Duration comes from the
    // scan-time MediaMetadataRetriever value which is accurate.
    var durationMs by remember(knownDurationMs) { mutableIntStateOf(knownDurationMs.toInt().coerceAtLeast(1)) }
    var isSeeking by remember { mutableStateOf(false) }
    // Wall-clock anchor: (anchorWallMs, anchorPosMs) means "at system clock
    // `anchorWallMs`, audio playhead was at `anchorPosMs`". We reset this on
    // play-start and on seek-finish.
    var anchorWallMs by remember { mutableLongStateOf(0L) }
    var anchorPosMs by remember { mutableIntStateOf(0) }

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
                val mp = player
                if (mp != null && mp.isPlaying) {
                    val elapsed = System.currentTimeMillis() - anchorWallMs
                    val posMs = (anchorPosMs + elapsed).toInt().coerceIn(0, durationMs)
                    progress = posMs.toFloat() / durationMs
                    onPositionUpdate(posMs)
                }
            }
            delay(30)
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
                        setOnCompletionListener {
                            progress = 1f
                            onPositionUpdate(0)
                            onPlayingChanged(false)
                        }
                        start()
                    }
                    anchorPosMs = 0
                    anchorWallMs = System.currentTimeMillis()
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
                        val seekTo = (progress * durationMs).toInt()
                        mp.seekTo(seekTo)
                        onPositionUpdate(seekTo)
                        anchorPosMs = seekTo
                        anchorWallMs = System.currentTimeMillis()
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
