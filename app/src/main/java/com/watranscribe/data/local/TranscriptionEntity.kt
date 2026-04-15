package com.watranscribe.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "transcriptions")
data class TranscriptionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val filename: String,
    val uri: String,
    val transcription: String?,
    @ColumnInfo(name = "duration_ms") val durationMs: Long,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "conversation_folder") val conversationFolder: String,
    @ColumnInfo(name = "last_modified") val lastModified: Long,
    @ColumnInfo(name = "status") val status: TranscriptionStatus = TranscriptionStatus.PENDING,
    @ColumnInfo(name = "segments_json", defaultValue = "") val segmentsJson: String = "",
    @ColumnInfo(name = "contact", defaultValue = "") val contact: String = "",
    @ColumnInfo(name = "model_used", defaultValue = "") val modelUsed: String = ""
)

/** A single timed segment: text with start/end in milliseconds */
data class TimedSegment(val text: String, val startMs: Long, val endMs: Long)

enum class TranscriptionStatus {
    PENDING, IN_PROGRESS, COMPLETED, FAILED
}
