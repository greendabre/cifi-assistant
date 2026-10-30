package com.cifi.assistant.util

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.cifi.assistant.MainActivity
import com.cifi.assistant.R
import com.cifi.assistant.service.CaptureService

object NotificationHelper {

    const val CHANNEL_ID = "cifi_capture_channel"
    const val NOTIFICATION_ID = 42001

    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = context.getString(R.string.notification_channel_name)
            val desc = context.getString(R.string.notification_channel_desc)
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = desc
                setShowBadge(false)
            }
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    fun buildForegroundNotification(context: Context, isOverlayVisible: Boolean = true): Notification {
        val launchIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val toggleIntent = Intent(context, CaptureService::class.java).apply {
            action = CaptureService.ACTION_TOGGLE_OVERLAY
        }
        val togglePendingIntent = PendingIntent.getService(
            context,
            1,
            toggleIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val copyIntent = Intent(context, CaptureService::class.java).apply {
            action = CaptureService.ACTION_COPY_DOSSIER
        }
        val copyPendingIntent = PendingIntent.getService(
            context,
            2,
            copyIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(context, CaptureService::class.java).apply {
            action = CaptureService.ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            context,
            3,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val toggleLabel = if (isOverlayVisible) "Hide Orb" else "Show Orb"
        val statusText = if (isOverlayVisible) {
            "Floating orb visible • Tap 'Hide Orb' to hide anytime"
        } else {
            "Floating orb hidden • Tap 'Show Orb' to display over CIFI"
        }

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(context.getString(R.string.service_running_title))
            .setContentText(statusText)
            .setContentIntent(pendingIntent)
            .addAction(0, toggleLabel, togglePendingIntent)
            .addAction(0, "Copy Dossier", copyPendingIntent)
            .addAction(0, "Turn Off", stopPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    fun updateNotification(context: Context, isOverlayVisible: Boolean) {
        if (!CaptureService.isServiceRunning) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildForegroundNotification(context, isOverlayVisible))
    }
}
