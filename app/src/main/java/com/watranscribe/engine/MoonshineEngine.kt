package com.watranscribe.engine

import ai.moonshine.voice.Transcriber
import ai.moonshine.voice.TranscriptEvent
import ai.moonshine.voice.TranscriptLine
import android.content.Context
import android.util.Log
import com.watranscribe.data.local.TimedSegment
import com.watranscribe.data.local.TimedWord
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Consumer
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "MoonshineEngine"
private const val SAMPLE_RATE = 16_000

/**
 * On-device speech-to-text backed by the official Moonshine Android SDK
 * (`ai.moonshine:moonshine-voice`).
 *
 * We use the **streaming** API (`createStream` + `addAudioToStream` +
 * `stopStream`) even when transcribing complete files, because it's the only
 * path that populates real per-word timings (via `TranscriptEvent.LineCompleted`
 * → `line.words`). The non-streaming `transcribeWithoutStreaming()` call
 * returns lines but leaves `words` empty, which would force us to synthesize
 * timings that drift out of sync with audio.
 *
 * Per the SDK sources, `addAudioToStream` and `stopStream` fire events
 * synchronously on the calling thread, so we can collect them deterministically
 * without any completion barrier.
 */
@Singleton
class MoonshineEngine @Inject constructor(
    @ApplicationContext private val context: Context
) : TranscriptionEngine {
    override val id: String = "moonshine"

    private val mutex = Mutex()
    private var transcriber: Transcriber? = null
    private var currentModel: String? = null

    override suspend fun loadModel(modelFilename: String): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (transcriber != null && currentModel == modelFilename) return@withLock

            transcriber?.let {
                Log.d(TAG, "Disposing previous Moonshine transcriber ($currentModel)")
            }
            transcriber = null
            currentModel = null

            val modelDir = File(context.filesDir, "models/$modelFilename")
            if (!modelDir.isDirectory) {
                error("Moonshine model directory not found: ${modelDir.absolutePath}. Download it from Settings.")
            }

            val info = ModelManager.AVAILABLE_MODELS.find { it.filename == modelFilename && it.engine == ENGINE_MOONSHINE }
                ?: error("No ModelInfo entry for Moonshine dir $modelFilename")

            val missing = info.moonshineFiles.filterNot { File(modelDir, it).exists() }
            if (missing.isNotEmpty()) {
                error("Moonshine model $modelFilename is incomplete. Missing: ${missing.joinToString()}")
            }

            Log.d(TAG, "Loading Moonshine arch=${info.moonshineArch} from ${modelDir.absolutePath}")
            val t = Transcriber()
            t.loadFromFiles(modelDir.absolutePath, info.moonshineArch)
            transcriber = t
            currentModel = modelFilename
            Log.d(TAG, "Moonshine loaded: $modelFilename (arch ${info.moonshineArch})")
        }
    }

    override fun getCurrentModelName(): String? = currentModel

    override suspend fun transcribeWithTimings(
        samples: FloatArray,
        segmentFlow: MutableSharedFlow<String>?
    ): TranscribeResult = withContext(Dispatchers.Default) {
        mutex.withLock {
            val t = transcriber ?: error("Moonshine model not loaded. Call loadModel() first.")
            val audioMs = (samples.size * 1000L) / SAMPLE_RATE
            Log.i(TAG, "PERF Starting: ${samples.size} samples (${audioMs / 1000.0}s audio) on $currentModel")

            // De-duped by line.id in case LineCompleted fires twice for the same line.
            val completed = ConcurrentHashMap<Long, TranscriptLine>()

            val listener = Consumer<TranscriptEvent> { event ->
                event.accept(object : TranscriptEvent.Visitor {
                    override fun onLineStarted(e: TranscriptEvent.LineStarted) {}
                    override fun onLineUpdated(e: TranscriptEvent.LineUpdated) {}
                    override fun onLineTextChanged(e: TranscriptEvent.LineTextChanged) {
                        // Live partial: emit accumulated text so the UI streams.
                        val partial = buildPartialText(completed.values, e.line)
                        segmentFlow?.tryEmit(partial)
                    }
                    override fun onLineCompleted(e: TranscriptEvent.LineCompleted) {
                        completed[e.line.id] = e.line
                        val partial = buildPartialText(completed.values, null)
                        segmentFlow?.tryEmit(partial)
                    }
                    override fun onError(e: TranscriptEvent.Error) {
                        Log.e(TAG, "Moonshine stream error", e.cause)
                    }
                })
            }
            t.addListener(listener)

            val t0 = System.nanoTime()
            val streamHandle = t.createStream()
            try {
                t.startStream(streamHandle)
                // Feed audio in ~1s chunks so addAudioToStream can fire LineStarted
                // / LineTextChanged / LineCompleted events incrementally — this is
                // what gives the UI a live-streaming experience during retranscribe.
                // A single add-all call would flush every event at the end.
                val chunkSize = SAMPLE_RATE // 1 second of mono 16 kHz float PCM
                var offset = 0
                while (offset < samples.size) {
                    val end = minOf(offset + chunkSize, samples.size)
                    val chunk = if (offset == 0 && end == samples.size) {
                        samples
                    } else {
                        samples.copyOfRange(offset, end)
                    }
                    t.addAudioToStream(streamHandle, chunk, SAMPLE_RATE)
                    offset = end
                }
                // stopStream flushes trailing audio and fires remaining LineCompleted events.
                t.stopStream(streamHandle)
            } finally {
                t.freeStream(streamHandle)
                t.removeListener(listener)
            }
            val inferMs = (System.nanoTime() - t0) / 1_000_000
            val rtf = if (audioMs > 0) inferMs.toDouble() / audioMs else 0.0

            // Sort completed lines by id (insertion order from Moonshine) and build segments.
            val orderedLines = completed.values.sortedBy { it.id }
            val segments = orderedLines.map { line -> toSegment(line) }
            val fullText = segments.joinToString(" ") { it.text }.trim()

            val totalWords = segments.sumOf { it.words.size }
            Log.i(TAG, "PERF Done: total=${inferMs}ms rtf=%.2fx lines=%d words=%d text=%d chars".format(rtf, segments.size, totalWords, fullText.length))
            if (fullText.isNotBlank()) Log.d(TAG, "Transcript: ${fullText.take(200)}${if (fullText.length > 200) "..." else ""}")

            segmentFlow?.tryEmit(fullText)
            TranscribeResult(text = fullText, segments = segments)
        }
    }

    override suspend fun release() {
        mutex.withLock {
            if (transcriber != null) {
                Log.d(TAG, "Releasing Moonshine transcriber ($currentModel)")
                transcriber = null
                currentModel = null
            }
        }
    }

    override fun isLoaded(): Boolean = transcriber != null

    private fun toSegment(line: TranscriptLine): TimedSegment {
        val startMs = (line.startTime * 1000).toLong()
        val endMs = ((line.startTime + line.duration) * 1000).toLong()
        val sdkWords = line.words.orEmpty()

        if (sdkWords.isNotEmpty()) {
            val words = sdkWords.map { w ->
                TimedWord(
                    text = w.word.orEmpty(),
                    startMs = (w.start * 1000).toLong(),
                    endMs = (w.end * 1000).toLong(),
                )
            }
            // Rebuild text from words — streaming Moonshine's line.text can drop
            // inter-word whitespace.
            return TimedSegment(
                text = words.joinToString(" ") { it.text },
                startMs = startMs,
                endMs = endMs,
                words = words,
            )
        }
        // No word-level timings — leave words empty and let the UI fall back to
        // line-level pop-in. The line's startTime/duration come from the model
        // and are accurate; synthetic per-word splits would drift out of sync.
        return TimedSegment(
            text = line.text.orEmpty(),
            startMs = startMs,
            endMs = endMs,
            words = emptyList(),
        )
    }

    private fun buildPartialText(
        finalized: Collection<TranscriptLine>,
        inProgress: TranscriptLine?,
    ): String {
        val lines = finalized.sortedBy { it.id }.mapNotNull { it.text }
        val tail = inProgress?.text
        val all = if (tail != null && finalized.none { it.id == inProgress.id }) lines + tail else lines
        return all.joinToString(" ").trim()
    }
}
