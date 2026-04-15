package com.watranscribe.engine

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.watranscribe.MainActivity
import com.watranscribe.R
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TranscriptionNotifier @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        const val CHANNEL_ID = "transcription_results"
        const val PROGRESS_CHANNEL_ID = "transcription_progress"
    }

    private val nextId = AtomicInteger(1000)

    private fun getOpenAppIntent(): PendingIntent {
        return PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    init {
        createChannels()
    }

    private fun createChannels() {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Transcription Results", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Shows completed voice note transcriptions"
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(PROGRESS_CHANNEL_ID, "Transcription Progress", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shows transcription in progress"
                setShowBadge(false)
            }
        )
    }

    fun notifyProgress(label: String, partialText: String): Int {
        if (!hasPermission()) return -1
        val id = nextId.getAndIncrement()
        val notification = NotificationCompat.Builder(context, PROGRESS_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(label)
            .setContentText(partialText.ifBlank { "Decoding audio..." })
            .setStyle(NotificationCompat.BigTextStyle().bigText(partialText.ifBlank { "Decoding audio..." }))
            .setOngoing(false)
            .setProgress(0, 0, true)
            .setSilent(true)
            .setContentIntent(getOpenAppIntent())
            .build()

        NotificationManagerCompat.from(context).notify(id, notification)
        return id
    }

    fun updateProgress(id: Int, label: String, partialText: String) {
        if (!hasPermission() || id < 0) return
        val notification = NotificationCompat.Builder(context, PROGRESS_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(label)
            .setContentText(partialText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(partialText))
            .setOngoing(false)
            .setProgress(0, 0, true)
            .setSilent(true)
            .setContentIntent(getOpenAppIntent())
            .build()

        NotificationManagerCompat.from(context).notify(id, notification)
    }

    fun notifyComplete(label: String, transcription: String) {
        if (!hasPermission()) return
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(label)
            .setContentText(transcription)
            .setStyle(NotificationCompat.BigTextStyle().bigText(transcription))
            .setAutoCancel(true)
            .setContentIntent(getOpenAppIntent())
            .build()

        NotificationManagerCompat.from(context).notify(nextId.getAndIncrement(), notification)
    }

    fun cancelProgress(id: Int) {
        if (id >= 0) {
            NotificationManagerCompat.from(context).cancel(id)
        }
    }

    private fun hasPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }
}
