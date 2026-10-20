package com.drops.miner

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat

class DropFarmerService : Service() {

    companion object {
        const val CHANNEL_ID = "TwitchDropMinerChannel"
        const val NOTIFICATION_ID = 101

        const val ACTION_UPDATE = "com.drops.miner.UPDATE_NOTIFICATION"
        const val EXTRA_DROP_TITLE = "extra_drop_title"
        const val EXTRA_PROGRESS = "extra_progress"
        const val EXTRA_STREAMER = "extra_streamer"
        const val EXTRA_MINUTES = "extra_minutes"

        fun updateProgress(context: Context, dropTitle: String, progress: String, streamer: String, minutes: Int) {
            val intent = Intent(context, DropFarmerService::class.java).apply {
                action = ACTION_UPDATE
                putExtra(EXTRA_DROP_TITLE, dropTitle)
                putExtra(EXTRA_PROGRESS, progress)
                putExtra(EXTRA_STREAMER, streamer)
                putExtra(EXTRA_MINUTES, minutes)
            }
            context.startService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Фарминг активен", "", "", 0))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null && intent.action == ACTION_UPDATE) {
            val dropTitle = intent.getStringExtra(EXTRA_DROP_TITLE) ?: "Фарминг активен"
            val progress = intent.getStringExtra(EXTRA_PROGRESS) ?: ""
            val streamer = intent.getStringExtra(EXTRA_STREAMER) ?: ""
            val minutes = intent.getIntExtra(EXTRA_MINUTES, 0)

            val notification = buildNotification(dropTitle, progress, streamer, minutes)
            val manager = getSystemService(NotificationManager::class.java)
            manager?.notify(NOTIFICATION_ID, notification)
        }
        return START_STICKY
    }

    private fun buildNotification(dropTitle: String, progressStr: String, streamer: String, minutes: Int): Notification {
        val progressInt = progressStr.replace("%", "").trim().toIntOrNull() ?: 0

        val title = if (progressStr.isNotEmpty()) {
            "🎁 $dropTitle — $progressStr"
        } else {
            "🎁 $dropTitle"
        }

        val content = if (streamer.isNotEmpty()) {
            "🟢 Стрим: $streamer | ▶️ $minutes мин"
        } else {
            "🔍 Поиск стримеров и проверка Drops..."
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setOnlyAlertOnce(true) // Не издает звук при каждом обновлении
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        // Добавляем шкалу прогресса, если процент известен
        if (progressInt > 0) {
            builder.setProgress(100, progressInt, false)
        }

        return builder.build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val serviceChannel = NotificationChannel(
            CHANNEL_ID,
            "Drop Miner Notifications",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Уведомления о прогрессе фарма Drops"
            setShowBadge(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(serviceChannel)
    }
}
