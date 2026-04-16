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
import java.util.ArrayDeque
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "TranscriptionRepo"
private const val MAX_REASONABLE_AUDIO_DURATION_MS = 7_200_000L // 2 hours
private val WEEK_FOLDER_REGEX = Regex("^\\d{6}$")
private val VOICE_NOTES_FOLDER_NAMES = setOf(
    "whatsapp voice notes",
    "whatsapp business voice notes"
)

@Singleton
class TranscriptionRepository @Inject constructor(
    private val dao: TranscriptionDao,
    private val engines: Map<String, @JvmSuppressWildcards TranscriptionEngine>,
    private val audioDecoder: AudioDecoder,
    private val prefsRepo: PreferencesRepository,
    private val modelManager: ModelManager,
    @ApplicationContext private val context: Context
) {
    private val resolvedVoiceRootCache = mutableMapOf<String, String>()

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
        val effectiveRootDocId = resolveVoiceNotesRootDocId(folderUri, rootDocId)
        if (effectiveRootDocId != rootDocId) {
            Log.d(TAG, "scanForNewFiles: auto-resolved voice notes folder docId=$effectiveRootDocId")
        }
        val rootChildrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(folderUri, effectiveRootDocId)

        // Step 1: list all week folders
        val weekFolders = mutableListOf<Pair<String, String>>() // docId, displayName
        var directOpusAtRoot = 0
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
                val folderName = cursor.getString(nameIdx) ?: continue
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR && isWeekFolderName(folderName)) {
                    weekFolders.add(cursor.getString(idIdx) to folderName)
                } else if (mime != DocumentsContract.Document.MIME_TYPE_DIR && folderName.endsWith(".opus", ignoreCase = true)) {
                    directOpusAtRoot++
                }
            }
        }
        if (weekFolders.isEmpty()) {
            val rootName = getDocumentDisplayName(folderUri, effectiveRootDocId)
            if (isWeekFolderName(rootName) || directOpusAtRoot > 0) {
                val pseudoName = rootName.ifBlank { "Selected Folder" }
                weekFolders.add(effectiveRootDocId to pseudoName)
                Log.w(
                    TAG,
                    "scanForNewFiles: no week folders at root; treating selected folder as scan root (name=$pseudoName directOpus=$directOpusAtRoot)"
                )
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
            } else if (!isSaneDurationMs(existing.durationMs) && isSaneDurationMs(entity.durationMs)) {
                // Backfill/sanitize duration for existing entries when we now have a sane value.
                dao.update(existing.copy(durationMs = entity.durationMs))
            }
        }

        Log.d(TAG, "scanForNewFiles: inserted $newCount new files (${batch.size} total found)")
        newCount
    }

    suspend fun getFolderResolutionHint(folderUri: Uri): String? = withContext(Dispatchers.IO) {
        val rootDocId = DocumentsContract.getTreeDocumentId(folderUri)
        val effectiveRootDocId = resolveVoiceNotesRootDocId(folderUri, rootDocId)
        if (effectiveRootDocId == rootDocId) return@withContext null

        val rootName = getDocumentDisplayName(folderUri, rootDocId)
        val effectiveName = getDocumentDisplayName(folderUri, effectiveRootDocId)
        if (effectiveName.isBlank()) return@withContext null

        if (rootName.isBlank()) {
            "Auto-detected folder: $effectiveName"
        } else {
            "Auto-detected $effectiveName inside $rootName"
        }
    }

    private fun getDuration(uri: Uri): Long {
        fun sanitizeDuration(ms: Long): Long = if (isSaneDurationMs(ms)) ms else 0L

        // Prefer MediaExtractor track duration first (usually most reliable for opus).
        try {
            val extractor = android.media.MediaExtractor()
            try {
                extractor.setDataSource(context, uri, null)
                for (i in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(i)
                    val mime = format.getString(android.media.MediaFormat.KEY_MIME) ?: continue
                    if (!mime.startsWith("audio/")) continue
                    val durUs = if (format.containsKey(android.media.MediaFormat.KEY_DURATION)) {
                        runCatching { format.getLong(android.media.MediaFormat.KEY_DURATION) }.getOrDefault(0L)
                    } else {
                        0L
                    }
                    val ms = sanitizeDuration(durUs / 1000L)
                    if (ms > 0) return ms
                    break
                }
            } finally {
                extractor.release()
            }
        } catch (_: Exception) {
            // Fall through to MediaMetadataRetriever fallback.
        }

        return try {
            val mmr = MediaMetadataRetriever()
            mmr.setDataSource(context, uri)
            val raw = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
            mmr.release()
            sanitizeDuration(raw)
        } catch (_: Exception) {
            0
        }
    }

    private fun parseWeekFolder(name: String): String {
        if (isWeekFolderName(name)) {
            val year = name.substring(0, 4)
            val week = name.substring(4, 6).trimStart('0')
            return "$year W$week"
        }
        return name
    }

    private fun isWeekFolderName(name: String): Boolean = WEEK_FOLDER_REGEX.matches(name)

    private fun resolveVoiceNotesRootDocId(treeUri: Uri, treeRootDocId: String): String {
        synchronized(resolvedVoiceRootCache) {
            resolvedVoiceRootCache[treeRootDocId]?.let { return it }
        }

        val resolved = resolveVoiceNotesRootDocIdUncached(treeUri, treeRootDocId)
        synchronized(resolvedVoiceRootCache) {
            resolvedVoiceRootCache[treeRootDocId] = resolved
        }
        return resolved
    }

    private fun resolveVoiceNotesRootDocIdUncached(treeUri: Uri, treeRootDocId: String): String {
        val rootName = getDocumentDisplayName(treeUri, treeRootDocId)
        if (isVoiceNotesFolderName(rootName)) return treeRootDocId

        // Fast deterministic paths from common user selections.
        val candidatePaths: List<List<List<String>>> = listOf(
            listOf(listOf("WhatsApp Voice Notes")),
            listOf(listOf("WhatsApp Business Voice Notes")),
            listOf(listOf("Media"), listOf("WhatsApp Voice Notes")),
            listOf(listOf("Media"), listOf("WhatsApp Business Voice Notes")),
            listOf(listOf("WhatsApp"), listOf("Media"), listOf("WhatsApp Voice Notes")),
            listOf(listOf("WhatsApp Business"), listOf("Media"), listOf("WhatsApp Business Voice Notes")),
            listOf(
                listOf("Android"),
                listOf("media"),
                listOf("com.whatsapp"),
                listOf("WhatsApp"),
                listOf("Media"),
                listOf("WhatsApp Voice Notes")
            ),
            listOf(
                listOf("Android"),
                listOf("media"),
                listOf("com.whatsapp.w4b"),
                listOf("WhatsApp Business", "WhatsApp"),
                listOf("Media"),
                listOf("WhatsApp Business Voice Notes", "WhatsApp Voice Notes")
            )
        )

        for (path in candidatePaths) {
            val docId = findDocIdByPath(treeUri, treeRootDocId, path) ?: continue
            if (isLikelyVoiceNotesFolder(treeUri, docId)) return docId
        }

        // Fallback: bounded BFS for anything named *Voice Notes*.
        return findVoiceNotesByBfs(treeUri, treeRootDocId) ?: treeRootDocId
    }

    private fun findDocIdByPath(
        treeUri: Uri,
        startDocId: String,
        path: List<List<String>>,
    ): String? {
        var current = startDocId
        for (segmentOptions in path) {
            val next = findChildDirectoryByName(treeUri, current, segmentOptions) ?: return null
            current = next
        }
        return current
    }

    private fun findChildDirectoryByName(
        treeUri: Uri,
        parentDocId: String,
        names: List<String>,
    ): String? {
        val wanted = names.map { it.lowercase(Locale.ROOT) }.toSet()
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        return try {
            context.contentResolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                ),
                null,
                null,
                null,
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                while (cursor.moveToNext()) {
                    val mime = cursor.getString(mimeIdx)
                    if (mime != DocumentsContract.Document.MIME_TYPE_DIR) continue
                    val name = cursor.getString(nameIdx) ?: continue
                    if (name.lowercase(Locale.ROOT) in wanted) {
                        return cursor.getString(idIdx)
                    }
                }
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun getDocumentDisplayName(treeUri: Uri, docId: String): String {
        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
        return try {
            context.contentResolver.query(
                docUri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use ""
                val idx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                cursor.getString(idx) ?: ""
            } ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    private fun isLikelyVoiceNotesFolder(treeUri: Uri, docId: String): Boolean {
        val name = getDocumentDisplayName(treeUri, docId)
        if (isVoiceNotesFolderName(name)) return true

        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
        return try {
            context.contentResolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                ),
                null,
                null,
                null,
            )?.use { cursor ->
                val nameIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                while (cursor.moveToNext()) {
                    val childName = cursor.getString(nameIdx) ?: continue
                    val mime = cursor.getString(mimeIdx)
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR && isWeekFolderName(childName)) {
                        return true
                    }
                    if (mime != DocumentsContract.Document.MIME_TYPE_DIR && childName.endsWith(".opus", ignoreCase = true)) {
                        return true
                    }
                }
                false
            } ?: false
        } catch (_: Exception) {
            false
        }
    }

    private fun findVoiceNotesByBfs(treeUri: Uri, startDocId: String): String? {
        data class Node(val docId: String, val depth: Int)

        val queue = ArrayDeque<Node>()
        val visited = hashSetOf<String>()
        queue.add(Node(startDocId, 0))
        visited.add(startDocId)

        val maxDepth = 6
        val maxVisited = 120

        while (queue.isNotEmpty() && visited.size <= maxVisited) {
            val node = queue.removeFirst()
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, node.docId)

            val children = try {
                context.contentResolver.query(
                    childrenUri,
                    arrayOf(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE,
                    ),
                    null,
                    null,
                    null,
                )?.use { cursor ->
                    val idIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                    val nameIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    val mimeIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                    val out = mutableListOf<Triple<String, String, String>>()
                    while (cursor.moveToNext()) {
                        out.add(
                            Triple(
                                cursor.getString(idIdx),
                                cursor.getString(nameIdx),
                                cursor.getString(mimeIdx)
                            )
                        )
                    }
                    out
                } ?: emptyList()
            } catch (_: Exception) {
                emptyList()
            }

            for ((childDocId, childName, childMime) in children) {
                if (childMime != DocumentsContract.Document.MIME_TYPE_DIR) continue
                val lower = childName.lowercase(Locale.ROOT)
                if (lower.contains("voice notes") && isLikelyVoiceNotesFolder(treeUri, childDocId)) {
                    return childDocId
                }
                if (node.depth < maxDepth && visited.add(childDocId)) {
                    queue.add(Node(childDocId, node.depth + 1))
                }
            }
        }
        return null
    }

    private fun isVoiceNotesFolderName(name: String): Boolean {
        return name.lowercase(Locale.ROOT) in VOICE_NOTES_FOLDER_NAMES
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
        val selectedModel = if (modelOverride != null) {
            check(modelManager.isDownloaded(modelOverride)) {
                "Model not downloaded: ${modelOverride.displayName}. Download it from Settings."
            }
            modelOverride
        } else {
            val selectedId = prefsRepo.modelSize.first()
            val preferred = ModelManager.AVAILABLE_MODELS.find { it.id == selectedId }
            val downloaded = modelManager.getDownloadedModels()
            when {
                preferred != null && modelManager.isDownloaded(preferred) -> preferred
                downloaded.isNotEmpty() -> downloaded.first()
                else -> error("No model downloaded. Go to Settings > Models and download one.")
            }
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

        if (!isSaneDurationMs(entity.durationMs) && isSaneDurationMs(decoded.durationMs)) {
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
    private fun isSaneDurationMs(ms: Long): Boolean = ms in 1L..MAX_REASONABLE_AUDIO_DURATION_MS
