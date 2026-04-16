package com.watranscribe.engine

import android.app.Notification
import android.content.ComponentName
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.watranscribe.worker.QuickTranscribeWorker

private const val TAG = "WANotifListener"
private const val WHATSAPP_PKG = "com.whatsapp"

/**
 * Listens for WhatsApp notifications to extract sender names for voice messages.
 * When a voice note notification arrives, we store the sender + timestamp and
 * trigger a quick scan+transcribe via WorkManager.
 *
 * This service is managed by Android — it's alive whenever the user has granted
 * notification access, and sleeps otherwise. No battery cost when idle.
 */
class WhatsAppNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "onListenerConnected — replaying active WhatsApp notifications")
        // Android doesn't auto-replay existing notifications to a freshly bound
        // listener (e.g. after app restart) — so we pull current ones ourselves.
        val active = try {
            activeNotifications
        } catch (t: Throwable) {
            Log.e(TAG, "activeNotifications threw", t)
            return
        }
        Log.d(TAG, "onListenerConnected: ${active.size} active notifications total")
        for (sbn in active) {
            if (sbn.packageName == WHATSAPP_PKG) {
                Log.d(TAG, "  replaying active WA notif id=${sbn.id} tag=${sbn.tag}")
                handleNotification(sbn)
            }
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        // Log every post to confirm the listener is alive and receiving events.
        // Filter to just watching WhatsApp after that.
        Log.v(TAG, "onNotificationPosted pkg=${sbn.packageName} id=${sbn.id}")
        if (sbn.packageName != WHATSAPP_PKG) return
        handleNotification(sbn)
    }

    private fun handleNotification(sbn: StatusBarNotification) {
        val extras = sbn.notification.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: return
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""

        val conversationTitle = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
        val isGroup = extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false)
        Log.d(TAG, "WhatsApp notif: title='$title' text='$text' convTitle='$conversationTitle' isGroup=$isGroup id=${sbn.id}")

        // First check EXTRA_MESSAGES (MessagingStyle) — this has the best data for groups
        val messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
        if (messages != null) {
            Log.d(TAG, "MessagingStyle: ${messages.size} messages")
            for (msg in messages) {
                if (msg is android.os.Bundle) {
                    val msgText = msg.getCharSequence("text")?.toString() ?: ""
                    val msgSender = msg.getCharSequence("sender")?.toString()
                    val msgTime = msg.getLong("time", sbn.postTime)
                    Log.d(TAG, "  msg: sender='$msgSender' text='$msgText' time=$msgTime")

                    if (msgText.contains("voice message", ignoreCase = true) || msgText.contains("\uD83C\uDFA4")) {
                        // For groups: use "Sender @ Group", for DMs just sender
                        val senderName = msgSender ?: title
                        val label = if (isGroup || conversationTitle != null) {
                            "$senderName @ ${conversationTitle ?: title}"
                        } else {
                            senderName
                        }
                        Log.d(TAG, "Voice message (messaging): '$label' at $msgTime")
                        PendingContactMatch.add(label, msgTime)
                        triggerQuickTranscribe(label)
                    }
                }
            }
            return
        }

        // Fallback: check EXTRA_TEXT directly (simple notifications)
        val isVoiceMessage = text.contains("voice message", ignoreCase = true)
                || text.contains("\uD83C\uDFA4")

        if (isVoiceMessage) {
            val sender = extractSender(title, text)
            val timestamp = sbn.postTime
            Log.d(TAG, "Voice message (simple): '$sender' at $timestamp")
            PendingContactMatch.add(sender, timestamp)
            triggerQuickTranscribe(sender)
        }
    }

    private fun extractSender(title: String, text: String): String {
        // Group format: "Sender: 🎤 Voice message (0:03)" or "Sender: Voice message (0:03)"
        // DM format: "🎤 Voice message (0:03)" (title is contact name)
        //
        // Strategy: look for "Name: " pattern before any voice/emoji indicator.
        // The colon-space separates the sender from the message content in groups.
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

        // For direct messages, title IS the contact name
        return title
    }

    private fun triggerQuickTranscribe(sender: String) {
        val work = OneTimeWorkRequestBuilder<QuickTranscribeWorker>()
            .setInputData(Data.Builder().putString("sender", sender).build())
            .build()
        WorkManager.getInstance(applicationContext)
            .enqueueUniqueWork("quick_transcribe", ExistingWorkPolicy.REPLACE, work)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {}

    companion object {
        fun isEnabled(context: android.content.Context): Boolean {
            val cn = ComponentName(context, WhatsAppNotificationListener::class.java)
            val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: ""
            return flat.contains(cn.flattenToString())
        }
    }
}

/**
 * In-memory buffer of recent voice message sender info.
 */
object PendingContactMatch {
    data class Entry(val sender: String, val timestamp: Long)

    private val entries = mutableListOf<Entry>()
    private val lock = Any()

    fun add(sender: String, timestamp: Long) {
        synchronized(lock) {
            entries.add(Entry(sender, timestamp))
            val cutoff = System.currentTimeMillis() - 3600_000
            entries.removeAll { it.timestamp < cutoff }
        }
    }

    fun consumeMatch(fileTimestamp: Long): String? {
        synchronized(lock) {
            val match = entries
                .filter { kotlin.math.abs(it.timestamp - fileTimestamp) < 60_000 }
                .minByOrNull { kotlin.math.abs(it.timestamp - fileTimestamp) }
            if (match != null) {
                entries.remove(match)
                return match.sender
            }
            return null
        }
    }
}
