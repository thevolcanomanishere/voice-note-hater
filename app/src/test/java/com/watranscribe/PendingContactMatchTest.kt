package com.watranscribe

import com.watranscribe.engine.PendingContactMatch
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

class PendingContactMatchTest {

    @Before
    fun setup() {
        val lockField = PendingContactMatch::class.java.getDeclaredField("lock")
        lockField.isAccessible = true
        val entriesField = PendingContactMatch::class.java.getDeclaredField("entries")
        entriesField.isAccessible = true

        val lock = lockField.get(PendingContactMatch) ?: return
        @Suppress("UNCHECKED_CAST")
        val entries = entriesField.get(PendingContactMatch) as MutableList<Any?>

        synchronized(lock) {
            entries.clear()
        }
    }

    @Test
    fun `consumeMatch returns sender within 60s window`() {
        val now = System.currentTimeMillis()
        PendingContactMatch.add("Alice", now)

        val result = PendingContactMatch.consumeMatch(now + 5_000) // 5s later
        assertEquals("Alice", result)
    }

    @Test
    fun `consumeMatch returns null outside 60s window`() {
        val now = System.currentTimeMillis()
        PendingContactMatch.add("Alice", now)

        val result = PendingContactMatch.consumeMatch(now + 120_000) // 2min later
        assertNull(result)
    }

    @Test
    fun `consumeMatch removes entry after consumption`() {
        val now = System.currentTimeMillis()
        PendingContactMatch.add("Alice", now)

        val first = PendingContactMatch.consumeMatch(now)
        assertEquals("Alice", first)

        val second = PendingContactMatch.consumeMatch(now)
        assertNull(second)
    }

    @Test
    fun `consumeMatch picks closest timestamp`() {
        val now = System.currentTimeMillis()
        PendingContactMatch.add("Alice", now)
        PendingContactMatch.add("Bob", now + 30_000)

        val result = PendingContactMatch.consumeMatch(now + 28_000)
        assertEquals("Bob", result)
    }

    @Test
    fun `add prunes entries older than 1 hour`() {
        val old = System.currentTimeMillis() - 3700_000 // 1h+ ago
        PendingContactMatch.add("OldEntry", old)
        // Adding a new entry triggers pruning
        PendingContactMatch.add("NewEntry", System.currentTimeMillis())

        val result = PendingContactMatch.consumeMatch(old)
        assertNull(result) // old entry should have been pruned
    }
}
