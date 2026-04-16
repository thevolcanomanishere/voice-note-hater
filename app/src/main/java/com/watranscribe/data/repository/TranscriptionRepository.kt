package com.watranscribe.data.repository

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import com.watranscribe.data.local.StatusStat
import com.watranscribe.data.local.TranscriptionDao
import com.watranscribe.data.local.TranscriptionEntity
import com.watranscribe.data.local.TranscriptionStatus
import com.watranscribe.engine.AudioDecoder
import com.watranscribe.engine.ModelInfo
import com.watranscribe.engine.ModelManager
import com.watranscribe.engine.PendingContactMatch
import com.watranscribe.engine.TranscriptionEngine
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "TranscriptionRepo"

@Singleton
class TranscriptionRepository @Inject constructor(
    private val dao: TranscriptionDao,
    private val engines: Map<String, @JvmSuppressWildcards TranscriptionEngine>,
    private val audioDecoder: AudioDecoder,
    private val prefsRepo: PreferencesRepository,
    @ApplicationContext private val context: Context
) {
    fun getAllTranscriptions(): Flow<List<TranscriptionEntity>> = dao.getAllTranscriptions()

    fun getConversationFolders(): Flow<List<String>> = dao.getConversationFolders()

    fun getByConversation(folder: String): Flow<List<TranscriptionEntity>> =
        dao.getByConversation(folder)

    fun search(query: String): Flow<List<TranscriptionEntity>> = dao.search(query)

    suspend fun getPending(): List<TranscriptionEntity> =
        dao.getByStatus(TranscriptionStatus.PENDING)

    suspend fun getNewestPending(limit: Int): List<TranscriptionEntity> =
        dao.getNewestPending(limit)

    /**
     * Fast scan using ContentResolver queries directly instead of DocumentFile.
     * Structure: root / YYYYWW / PTT-YYYYMMDD-WANNNN.opus
     */
    suspend fun scanForNewFiles(folderUri: Uri): Int = withContext(Dispatchers.IO) {
        Log.d(TAG, "scanForNewFiles: starting with URI=$folderUri")
        val resolver = context.contentResolver

        val rootDocId = DocumentsContract.getTreeDocumentId(folderUri)
        val rootChildrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(folderUri, rootDocId)

        // Step 1: list all week folders
        val weekFolders = mutableListOf<Pair<String, String>>() // docId, displayName
        resolver.query(
            rootChildrenUri,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE),
            null, null, null
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            while (cursor.moveToNext()) {
                val mime = cursor.getString(mimeIdx)
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    weekFolders.add(cursor.getString(idIdx) to cursor.getString(nameIdx))
                }
            }
        }
        Log.d(TAG, "scanForNewFiles: found ${weekFolders.size} week folders")

        // Step 2: query each week folder for .opus files
        var newCount = 0
        val batch = mutableListOf<TranscriptionEntity>()

        for ((docId, folderName) in weekFolders) {
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(folderUri, docId)
            val weekLabel = parseWeekFolder(folderName)

            resolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                    DocumentsContract.Document.COLUMN_SIZE
                ),
                null, null, null
            )?.use { cursor ->
                val nameIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val modIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                val fileIdIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)

                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameIdx)
                    if (!name.endsWith(".opus", ignoreCase = true)) continue
                    val lastMod = cursor.getLong(modIdx)
                    val fileDocId = cursor.getString(fileIdIdx)
                    val fileUri = DocumentsContract.buildDocumentUriUsingTree(folderUri, fileDocId)

                    val durationMs = getDuration(fileUri)
                    batch.add(
                        TranscriptionEntity(
                            filename = name,
                            uri = fileUri.toString(),
                            transcription = null,
                            durationMs = durationMs,
                            conversationFolder = weekLabel,
                            lastModified = lastMod,
                            createdAt = parseDateFromFilename(name) ?: lastMod
                        )
                    )
                }
            }
        }

        // Step 3: bulk insert, skipping existing. Try to match contacts for new files.
        for (entity in batch) {
            val existing = dao.findByFileAndModified(entity.filename, entity.lastModified)
            if (existing == null) {
                val contact = PendingContactMatch.consumeMatch(entity.lastModified) ?: ""
                dao.insert(entity.copy(contact = contact))
                newCount++
            } else if (existing.durationMs == 0L && entity.durationMs > 0) {
                // Backfill duration for existing entries
                dao.update(existing.copy(durationMs = entity.durationMs))
            }
        }

        Log.d(TAG, "scanForNewFiles: inserted $newCount new files (${batch.size} total found)")
        newCount
    }

    private fun getDuration(uri: Uri): Long {
        return try {
            val mmr = MediaMetadataRetriever()
            mmr.setDataSource(context, uri)
            val ms = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
            mmr.release()
            ms
        } catch (_: Exception) {
            0
        }
    }

    private fun parseWeekFolder(name: String): String {
        if (name.length == 6) {
            val year = name.substring(0, 4)
            val week = name.substring(4, 6).trimStart('0')
            return "$year W$week"
        }
        return name
    }

    private fun parseDateFromFilename(name: String): Long? {
        val match = Regex("PTT-(\\d{4})(\\d{2})(\\d{2})-").find(name) ?: return null
        return try {
            val (y, m, d) = match.destructured
            val cal = java.util.Calendar.getInstance().apply {
                set(y.toInt(), m.toInt() - 1, d.toInt(), 0, 0, 0)
                set(java.util.Calendar.MILLISECOND, 0)
            }
            cal.timeInMillis
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Transcribe with streaming: emits partial text via [onSegment] as each segment completes.
     *
     * @param modelOverride if non-null, use this model instead of the user's pref (for retranscription).
     */
    suspend fun transcribe(
        entity: TranscriptionEntity,
        modelOverride: ModelInfo? = null,
        onSegment: ((String) -> Unit)? = null
    ): Result<String> = runCatching {
        val totalStart = System.nanoTime()
        dao.updateStatus(entity.id, TranscriptionStatus.IN_PROGRESS)

        // Load the user's selected model (or the override)
        val modelStart = System.nanoTime()
        val selectedModel = modelOverride ?: run {
            val selectedId = prefsRepo.modelSize.first()
            ModelManager.AVAILABLE_MODELS.find { it.id == selectedId }
                ?: ModelManager.AVAILABLE_MODELS.first { it.id == "base.en" }
        }
        val engine = engines[selectedModel.engine]
            ?: error("No engine registered for '${selectedModel.engine}' (model ${selectedModel.id})")
        engine.loadModel(selectedModel.filename)
        val modelMs = (System.nanoTime() - modelStart) / 1_000_000
        Log.d(TAG, "PERF model_load=${modelMs}ms (${engine.getCurrentModelName()} on ${selectedModel.engine})")

        // Decode audio
        val decodeStart = System.nanoTime()
        val uri = Uri.parse(entity.uri)
        val decoded = audioDecoder.decode(uri)
        val decodeMs = (System.nanoTime() - decodeStart) / 1_000_000
        val audioSec = decoded.durationMs / 1000.0
        Log.d(TAG, "PERF decode=${decodeMs}ms audio=${audioSec}s samples=${decoded.samples.size}")

        if (entity.durationMs == 0L && decoded.durationMs > 0) {
            dao.update(entity.copy(durationMs = decoded.durationMs))
        }

        val flow = if (onSegment != null) {
            kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 64)
        } else null

        // Transcribe
        val whisperStart = System.nanoTime()
        val result = kotlinx.coroutines.coroutineScope {
            val collectJob = if (flow != null && onSegment != null) {
                launch(Dispatchers.Main) {
                    // Engines emit the full running transcript each time (Moonshine
                    // streams partial-then-completed, whisper accumulates inside the
                    // WhisperEngine callback). Just display the latest.
                    flow.collect { currentText ->
                        onSegment(currentText)
                        dao.updateTranscription(entity.id, currentText.trim(), TranscriptionStatus.IN_PROGRESS)
                    }
                }
            } else null

            try {
                engine.transcribeWithTimings(decoded.samples, flow)
            } finally {
                collectJob?.cancel()
            }
        }
        val inferMs = (System.nanoTime() - whisperStart) / 1_000_000
        val totalMs = (System.nanoTime() - totalStart) / 1_000_000
        val rtf = if (decoded.durationMs > 0) inferMs.toDouble() / decoded.durationMs else 0.0
        Log.d(TAG, "PERF infer=${inferMs}ms engine=${selectedModel.engine} segments=${result.segments.size} rtf=%.2fx".format(rtf))
        Log.d(TAG, "PERF total=${totalMs}ms (model=${modelMs} decode=${decodeMs} infer=${inferMs}) audio=${audioSec}s file=${entity.filename}")

        val segmentsJson = com.watranscribe.data.local.SegmentsCodec.encode(result.segments)
        val modelName = engine.getCurrentModelName()
            ?.removePrefix("ggml-")?.removeSuffix(".bin") ?: selectedModel.id
        Log.d(TAG, "DB write: id=${entity.id} text=${result.text.trim().length}chars segments_json=${segmentsJson.length}bytes model=$modelName")
        dao.updateTranscriptionWithSegments(entity.id, result.text.trim(), TranscriptionStatus.COMPLETED, segmentsJson, modelName)

        result.text.trim()
    }.onFailure {
        Log.e(TAG, "transcribe failed for ${entity.filename}", it)
        dao.updateStatus(entity.id, TranscriptionStatus.FAILED)
    }

    suspend fun count(): Int = dao.count()

    fun observeStatusStats(): Flow<List<StatusStat>> = dao.observeStatusStats()

    suspend fun getPendingAndFailed(): List<TranscriptionEntity> = dao.getPendingAndFailed()

    suspend fun clearAllTranscriptions(): Int = withContext(Dispatchers.IO) {
        dao.clearAllTranscriptions()
    }
}
