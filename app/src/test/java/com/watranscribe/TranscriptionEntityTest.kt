package com.watranscribe

import com.watranscribe.data.local.TranscriptionEntity
import com.watranscribe.data.local.TranscriptionStatus
import org.junit.Test
import org.junit.Assert.*

class TranscriptionEntityTest {

    @Test
    fun `default entity has correct defaults`() {
        val entity = TranscriptionEntity(
            filename = "test.opus",
            uri = "content://test",
            transcription = null,
            durationMs = 0,
            conversationFolder = "2026 W16",
            lastModified = 1000L
        )
        assertEquals(0L, entity.id)
        assertEquals(TranscriptionStatus.PENDING, entity.status)
        assertEquals("", entity.segmentsJson)
        assertEquals("", entity.contact)
        assertNull(entity.transcription)
    }

    @Test
    fun `entity copy preserves fields`() {
        val entity = TranscriptionEntity(
            id = 1,
            filename = "test.opus",
            uri = "content://test",
            transcription = "hello",
            durationMs = 5000,
            conversationFolder = "2026 W16",
            lastModified = 1000L,
            status = TranscriptionStatus.COMPLETED,
            contact = "Alice",
            segmentsJson = "0|5000|hello"
        )
        val copy = entity.copy(transcription = "updated")
        assertEquals("updated", copy.transcription)
        assertEquals("Alice", copy.contact)
        assertEquals(TranscriptionStatus.COMPLETED, copy.status)
        assertEquals(1L, copy.id)
    }

    @Test
    fun `status enum values exist`() {
        assertEquals(4, TranscriptionStatus.entries.size)
        assertNotNull(TranscriptionStatus.valueOf("PENDING"))
        assertNotNull(TranscriptionStatus.valueOf("IN_PROGRESS"))
        assertNotNull(TranscriptionStatus.valueOf("COMPLETED"))
        assertNotNull(TranscriptionStatus.valueOf("FAILED"))
    }

    @Test
    fun `contact display logic`() {
        val withContact = TranscriptionEntity(
            filename = "PTT-20260415-WA0001.opus",
            uri = "", transcription = null, durationMs = 0,
            conversationFolder = "", lastModified = 0, contact = "Alice"
        )
        val withoutContact = TranscriptionEntity(
            filename = "PTT-20260415-WA0001.opus",
            uri = "", transcription = null, durationMs = 0,
            conversationFolder = "", lastModified = 0
        )
        // UI logic: show contact if present, else filename
        assertEquals("Alice", withContact.contact.ifBlank { withContact.filename })
        assertEquals("PTT-20260415-WA0001.opus", withoutContact.contact.ifBlank { withoutContact.filename })
    }
}
