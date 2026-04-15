package com.watranscribe

import org.junit.Test
import org.junit.Assert.*
import java.util.Calendar
import java.util.TimeZone

class DateParsingTest {

    // Mirror the repository's parsing logic
    private fun parseDateFromFilename(name: String): Long? {
        val match = Regex("PTT-(\\d{4})(\\d{2})(\\d{2})-").find(name) ?: return null
        return try {
            val (y, m, d) = match.destructured
            val cal = Calendar.getInstance().apply {
                set(y.toInt(), m.toInt() - 1, d.toInt(), 0, 0, 0)
                set(Calendar.MILLISECOND, 0)
            }
            cal.timeInMillis
        } catch (_: Exception) {
            null
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

    @Test
    fun `parseDateFromFilename extracts correct date`() {
        val result = parseDateFromFilename("PTT-20260415-WA0019.opus")
        assertNotNull(result)
        val cal = Calendar.getInstance().apply { timeInMillis = result!! }
        assertEquals(2026, cal.get(Calendar.YEAR))
        assertEquals(Calendar.APRIL, cal.get(Calendar.MONTH))
        assertEquals(15, cal.get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun `parseDateFromFilename returns null for non-matching filename`() {
        assertNull(parseDateFromFilename("random-file.opus"))
        assertNull(parseDateFromFilename("AUD-20260415-WA0001.opus"))
        assertNull(parseDateFromFilename(""))
    }

    @Test
    fun `parseDateFromFilename handles different dates`() {
        val jan = parseDateFromFilename("PTT-20210101-WA0001.opus")
        assertNotNull(jan)
        val cal = Calendar.getInstance().apply { timeInMillis = jan!! }
        assertEquals(2021, cal.get(Calendar.YEAR))
        assertEquals(Calendar.JANUARY, cal.get(Calendar.MONTH))
        assertEquals(1, cal.get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun `parseWeekFolder formats correctly`() {
        assertEquals("2026 W16", parseWeekFolder("202616"))
        assertEquals("2021 W1", parseWeekFolder("202101"))
        assertEquals("2025 W52", parseWeekFolder("202552"))
    }

    @Test
    fun `parseWeekFolder passes through non-standard names`() {
        assertEquals("some-folder", parseWeekFolder("some-folder"))
        assertEquals("12345", parseWeekFolder("12345"))
        assertEquals("1234567", parseWeekFolder("1234567"))
    }

    @Test
    fun `parseWeekFolder trims leading zero from week`() {
        assertEquals("2021 W1", parseWeekFolder("202101"))
        assertEquals("2021 W9", parseWeekFolder("202109"))
        assertEquals("2021 W10", parseWeekFolder("202110"))
    }
}
