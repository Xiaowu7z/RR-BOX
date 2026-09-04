package com.rr.client.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.rr.client.MainActivity
import com.rr.client.R
import com.rr.client.traffic.TrafficSpeed

class RRNotificationManager(private val context: Context) {
    companion object {
        const val CHANNEL_ID = "rrbox_status_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_STOP_VPN = "com.rr.client.ACTION_STOP_VPN"
        const val ACTION_RESTART_VPN = "com.rr.client.ACTION_RESTART_VPN"
    }

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = context.getString(R.string.notification_channel_desc)
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
                setSound(null, null)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun buildNotification(
        nodeTag: String,
        speed: TrafficSpeed,
        durationSeconds: Long
    ): Notification {
        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingOpenApp = PendingIntent.getActivity(
            context,
            0,
            openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val restartIntent = Intent(context, RRVpnService::class.java).apply {
            action = ACTION_RESTART_VPN
        }
        val pendingRestart = PendingIntent.getService(
            context,
            1,
            restartIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(context, RRVpnService::class.java).apply {
            action = ACTION_STOP_VPN
        }
        val pendingStop = PendingIntent.getService(
            context,
            2,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val collapsedText = "↓ ${speed.formattedDownSpeed}   ↑ ${speed.formattedUpSpeed}"

        val hours = durationSeconds / 3600
        val minutes = (durationSeconds % 3600) / 60
        val seconds = durationSeconds % 60
        val durationFormatted = String.format("%02d:%02d:%02d", hours, minutes, seconds)

        val expandedText = "节点: $nodeTag\n下行速率: ${speed.formattedDownSpeed}\n上行速率: ${speed.formattedUpSpeed}\n已连接: $durationFormatted"

        return NotificationCompat.Builder(context, CHANNEL_ID)
            // This is the icon shown on the LEFT of the notification card and in the status bar.
            // Android small icons are system-tinted monochrome masks, so this drawable mirrors
            // the current RRBOX launcher artwork as a dedicated R + portrait silhouette.
            .setSmallIcon(R.drawable.ic_rrbox_status)
            // Intentionally no setLargeIcon(): the right side of the notification card stays empty.
            .setContentTitle("RRBOX · $nodeTag")
            .setContentText(collapsedText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(expandedText))
            .setContentIntent(pendingOpenApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, context.getString(R.string.action_restart), pendingRestart)
            .addAction(0, context.getString(R.string.action_stop), pendingStop)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    fun updateNotification(
        nodeTag: String,
        speed: TrafficSpeed,
        durationSeconds: Long
    ) {
        val notification = buildNotification(nodeTag, speed, durationSeconds)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }
}
