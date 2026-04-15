package com.watranscribe

import org.junit.Test
import org.junit.Assert.*

class NotificationParsingTest {

    // Mirror the notification listener's sender extraction logic
    private fun extractSender(title: String, text: String): String {
        val colonSpace = text.indexOf(": ")
        if (colonSpace > 0) {
            val afterColon = text.substring(colonSpace + 2)
            val isVoiceAfterColon = afterColon.contains("voice message", ignoreCase = true)
                    || afterColon.startsWith("\uD83C\uDFA4")
                    || afterColon.startsWith("🎤")
            if (isVoiceAfterColon) {
                return text.substring(0, colonSpace).trim()
            }
        }
        return title
    }

    @Test
    fun `DM voice message uses title as sender`() {
        val sender = extractSender("Alice", "\uD83C\uDFA4 Voice message (0:03)")
        assertEquals("Alice", sender)
    }

    @Test
    fun `group voice message extracts sender from colon prefix`() {
        val sender = extractSender("Group Chat", "Bob: \uD83C\uDFA4 Voice message (0:15)")
        assertEquals("Bob", sender)
    }

    @Test
    fun `group voice message with text Voice message`() {
        val sender = extractSender("Family", "Mum: Voice message (0:45)")
        assertEquals("Mum", sender)
    }

    @Test
    fun `DM with no colon prefix falls back to title`() {
        val sender = extractSender("Charlie", "Voice message (0:10)")
        assertEquals("Charlie", sender)
    }

    @Test
    fun `emoji in title preserved`() {
        val sender = extractSender("Klara \uD83D\uDE07", "\uD83C\uDFA4 Voice message (0:03)")
        assertEquals("Klara \uD83D\uDE07", sender)
    }

    @Test
    fun `non-voice colon text does not extract sender`() {
        // "Hey: what's up" — colon is part of normal text, not a sender prefix
        val sender = extractSender("Alice", "Hey: what's up")
        assertEquals("Alice", sender)
    }

    @Test
    fun `group with emoji sender`() {
        val sender = extractSender("Work Chat", "Dave \uD83D\uDE0E: Voice message (1:23)")
        assertEquals("Dave \uD83D\uDE0E", sender)
    }
}
