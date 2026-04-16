package com.watranscribe.data.local

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** Aggregate row from [TranscriptionDao.observeStatusStats]. */
data class StatusStat(
    @ColumnInfo(name = "status") val status: TranscriptionStatus,
    @ColumnInfo(name = "cnt") val count: Int,
    @ColumnInfo(name = "dur") val durationMs: Long,
)

@Dao
interface TranscriptionDao {

    @Query("SELECT * FROM transcriptions ORDER BY last_modified DESC")
    fun getAllTranscriptions(): Flow<List<TranscriptionEntity>>

    @Query(
        "SELECT * FROM transcriptions WHERE conversation_folder = :folder ORDER BY last_modified DESC"
    )
    fun getByConversation(folder: String): Flow<List<TranscriptionEntity>>

    @Query(
        "SELECT DISTINCT conversation_folder FROM transcriptions ORDER BY conversation_folder DESC"
    )
    fun getConversationFolders(): Flow<List<String>>

    @Query(
        "SELECT * FROM transcriptions WHERE transcription LIKE '%' || :query || '%' ORDER BY last_modified DESC"
    )
    fun search(query: String): Flow<List<TranscriptionEntity>>

    @Query("SELECT * FROM transcriptions WHERE status = :status ORDER BY last_modified DESC")
    suspend fun getByStatus(status: TranscriptionStatus): List<TranscriptionEntity>

    @Query("SELECT * FROM transcriptions WHERE status = 'PENDING' ORDER BY last_modified DESC LIMIT :limit")
    suspend fun getNewestPending(limit: Int): List<TranscriptionEntity>

    @Query("SELECT * FROM transcriptions WHERE filename = :filename AND last_modified = :lastModified LIMIT 1")
    suspend fun findByFileAndModified(filename: String, lastModified: Long): TranscriptionEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(transcription: TranscriptionEntity): Long

    @Update
    suspend fun update(transcription: TranscriptionEntity)

    @Query("UPDATE transcriptions SET transcription = :text, status = :status, segments_json = :segmentsJson, model_used = :modelUsed WHERE id = :id")
    suspend fun updateTranscriptionWithSegments(id: Long, text: String, status: TranscriptionStatus, segmentsJson: String, modelUsed: String = "")

    @Query("UPDATE transcriptions SET transcription = :text, status = :status WHERE id = :id")
    suspend fun updateTranscription(id: Long, text: String, status: TranscriptionStatus)

    @Query("UPDATE transcriptions SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: Long, status: TranscriptionStatus)

    @Query("UPDATE transcriptions SET contact = :contact WHERE id = :id")
    suspend fun updateContact(id: Long, contact: String)

    @Query("SELECT * FROM transcriptions WHERE contact = '' AND created_at >= :sinceMs ORDER BY last_modified DESC")
    suspend fun getRecentWithoutContact(sinceMs: Long): List<TranscriptionEntity>

    @Query("SELECT COUNT(*) FROM transcriptions")
    suspend fun count(): Int

    @Query(
        "SELECT status AS status, COUNT(*) AS cnt, COALESCE(SUM(duration_ms), 0) AS dur " +
        "FROM transcriptions GROUP BY status"
    )
    fun observeStatusStats(): Flow<List<StatusStat>>

    @Query(
        "SELECT * FROM transcriptions WHERE status IN ('PENDING', 'FAILED') ORDER BY last_modified DESC"
    )
    suspend fun getPendingAndFailed(): List<TranscriptionEntity>

    @Query(
        "UPDATE transcriptions SET transcription = NULL, segments_json = '', model_used = '', status = 'PENDING'"
    )
    suspend fun clearAllTranscriptions(): Int
}
