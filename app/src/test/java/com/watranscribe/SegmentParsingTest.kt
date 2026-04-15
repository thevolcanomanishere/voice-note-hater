package com.watranscribe

import com.watranscribe.data.local.TimedSegment
import org.junit.Test
import org.junit.Assert.*

class SegmentParsingTest {

    private fun parseSegments(raw: String): List<TimedSegment> {
        return raw.lines().filter { it.isNotBlank() }.mapNotNull { line ->
            val parts = line.split("|", limit = 3)
            if (parts.size == 3) {
                TimedSegment(
                    text = parts[2],
                    startMs = parts[0].toLongOrNull() ?: 0,
                    endMs = parts[1].toLongOrNull() ?: 0
                )
            } else null
        }
    }

    @Test
    fun `parse valid segments`() {
        val raw = "0|2500| Hello\n2500|5000| world\n"
        val segments = parseSegments(raw)
        assertEquals(2, segments.size)
        assertEquals(" Hello", segments[0].text)
        assertEquals(0L, segments[0].startMs)
        assertEquals(2500L, segments[0].endMs)
        assertEquals(" world", segments[1].text)
        assertEquals(2500L, segments[1].startMs)
        assertEquals(5000L, segments[1].endMs)
    }

    @Test
    fun `parse empty string returns empty list`() {
        val segments = parseSegments("")
        assertTrue(segments.isEmpty())
    }

    @Test
    fun `parse malformed lines are skipped`() {
        val raw = "0|2500| Hello\nbad line\n2500|5000| world\n"
        val segments = parseSegments(raw)
        assertEquals(2, segments.size)
    }

    @Test
    fun `parse segment with pipes in text`() {
        // limit=3 means only first two pipes are split
        val raw = "0|2500|text with | pipe\n"
        val segments = parseSegments(raw)
        assertEquals(1, segments.size)
        assertEquals("text with | pipe", segments[0].text)
    }

    @Test
    fun `parse invalid timestamps default to zero`() {
        val raw = "abc|def| Hello\n"
        val segments = parseSegments(raw)
        assertEquals(1, segments.size)
        assertEquals(0L, segments[0].startMs)
        assertEquals(0L, segments[0].endMs)
    }
}
