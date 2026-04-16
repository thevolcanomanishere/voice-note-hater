package com.watranscribe

import com.watranscribe.data.local.SegmentsCodec
import com.watranscribe.data.local.TimedSegment
import org.junit.Test
import org.junit.Assert.*

class SegmentParsingTest {

    @Test
    fun `parse valid segments`() {
        val raw = "0|2500| Hello\n2500|5000| world\n"
        val segments = SegmentsCodec.parse(raw)
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
        val segments = SegmentsCodec.parse("")
        assertTrue(segments.isEmpty())
    }

    @Test
    fun `parse malformed lines are skipped`() {
        val raw = "0|2500| Hello\nbad line\n2500|5000| world\n"
        val segments = SegmentsCodec.parse(raw)
        assertEquals(2, segments.size)
    }

    @Test
    fun `parse segment with pipes in text`() {
        val raw = "0|2500|text with | pipe\n"
        val segments = SegmentsCodec.parse(raw)
        assertEquals(1, segments.size)
        assertEquals("text with | pipe", segments[0].text)
    }

    @Test
    fun `parse invalid timestamps default to zero`() {
        val raw = "abc|def| Hello\n"
        val segments = SegmentsCodec.parse(raw)
        assertEquals(1, segments.size)
        assertEquals(0L, segments[0].startMs)
        assertEquals(0L, segments[0].endMs)
    }

    @Test
    fun `parse invalid json returns empty list`() {
        val parsed = SegmentsCodec.parse("[{bad json")
        assertTrue(parsed.isEmpty())
    }
}
