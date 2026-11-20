package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.data.telegram.BotConnectionStatus
import com.example.di.YutaAppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Play-Policy compliant dataSync Foreground Service that keeps Telegram sticker queue polling
 * active on the tablet while showing a transparent persistent notification.
 */
class YutaStickerMonitorService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var monitorJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopMonitoring()
            return START_NOT_STICKY
        }

        val notification = buildNotification("Starting 24/7 Sticker Monitor...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        val container = YutaAppContainer.getInstance(applicationContext)
        container.botEngine.setForegroundServiceActive(true)
        if (container.botEngine.runtimeState.value.status == BotConnectionStatus.DISCONNECTED ||
            container.botEngine.runtimeState.value.status == BotConnectionStatus.ERROR
        ) {
            container.botEngine.startPolling()
        }

        monitorJob?.cancel()
        monitorJob = serviceScope.launch {
            combine(
                container.botEngine.runtimeState,
                container.dao.observeStickerScannerStats()
            ) { runtime, stats ->
                "${runtime.status.label} • Received: ${stats.totalStickersReceived} | Scanned: ${stats.totalStickersScanned} | Pending: ${stats.pendingScans}"
            }.collect { summary ->
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(NOTIFICATION_ID, buildNotification(summary))
            }
        }

        return START_STICKY
    }

    private fun stopMonitoring() {
        val container = YutaAppContainer.getInstance(applicationContext)
        container.botEngine.setForegroundServiceActive(false)
        container.botEngine.stopPolling()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        monitorJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Yuta AI Sticker Monitor",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps Telegram group sticker moderation queue active on tablet"
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(contentText: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPending = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, YutaStickerMonitorService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Yuta AI • 24/7 Sticker Guard")
            .setContentText(contentText)
            .setOngoing(true)
            .setContentIntent(openPending)
            .addAction(0, "Stop", stopPending)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "yuta_sticker_monitor"
        private const val NOTIFICATION_ID = 2407
        private const val ACTION_STOP = "com.yuta.ai.ACTION_STOP_MONITOR"

        fun start(context: Context) {
            val intent = Intent(context, YutaStickerMonitorService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, YutaStickerMonitorService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
