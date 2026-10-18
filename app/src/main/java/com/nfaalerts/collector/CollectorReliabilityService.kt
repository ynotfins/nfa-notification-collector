package com.nfaalerts.collector

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.service.notification.NotificationListenerService
import com.nfaalerts.collector.capture.NfaNotificationListenerService
import com.nfaalerts.collector.capture.NotificationAccessStatus
import com.nfaalerts.collector.capture.ShadeReconcilePace

class CollectorReliabilityService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val watchdog =
        object : Runnable {
            override fun run() {
                if (!hasNotificationAccess(this@CollectorReliabilityService)) {
                    stopSelf()
                    return
                }
                val connected = listenerConnected()
                if (!connected) {
                    requestCollectorListenerRebind(this@CollectorReliabilityService)
                }
                val delayMs =
                    if (connected) {
                        WATCHDOG_CONNECTED_INTERVAL_MS
                    } else {
                        ShadeReconcilePace.DEGRADED_INTERVAL_MS
                    }
                handler.postDelayed(this, delayMs)
            }
        }

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Collector reliability",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Keeps notification capture and ordered outbox recovery visible."
                lockscreenVisibility = Notification.VISIBILITY_SECRET
                setShowBadge(false)
            },
        )
        startForeground(NOTIFICATION_ID, notification())
        foregroundPosted = true
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        if (!hasNotificationAccess(this)) {
            handler.removeCallbacks(watchdog)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!listenerConnected()) {
            requestCollectorListenerRebind(this)
        }
        handler.removeCallbacks(watchdog)
        handler.postDelayed(watchdog, ShadeReconcilePace.DEGRADED_INTERVAL_MS)
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(watchdog)
        foregroundPosted = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun listenerConnected(): Boolean {
        val app = application as? NfaCollectorApp ?: return false
        return app.appContainer.listenerStatus.state.value.connected
    }

    private fun notification(): Notification {
        val contentIntent =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val builder =
            Notification
                .Builder(this, CHANNEL_ID)
                .setSmallIcon(com.nfaalerts.collector.R.drawable.ic_collector)
                .setContentTitle("NFA Collector is protecting capture")
                .setContentText("Notifications are persisted locally before ordered delivery.")
                .setContentIntent(contentIntent)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setGroup(GROUP_KEY)
                .setGroupSummary(false)
                .setVisibility(Notification.VISIBILITY_SECRET)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return builder.build()
    }

    companion object {
        private const val CHANNEL_ID = "collector-reliability"
        private const val GROUP_KEY = "com.nfaalerts.collector.reliability"
        private const val NOTIFICATION_ID = 2701
        private const val WATCHDOG_CONNECTED_INTERVAL_MS = 5 * 60_000L

        @Volatile
        private var foregroundPosted = false

        fun update(context: Context) {
            val intent = Intent(context, CollectorReliabilityService::class.java)
            if (!hasNotificationAccess(context)) {
                foregroundPosted = false
                context.stopService(intent)
                return
            }
            if (foregroundPosted) return
            runCatching { context.startForegroundService(intent) }
        }
    }
}

class CollectorRestartReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        requestCollectorListenerRebind(context)
        CollectorReliabilityService.update(context)
        CaptureKeepAliveScheduler.ensure(context)
        (context.applicationContext as? NfaCollectorApp)?.appContainer?.onConnectivityAvailable()
    }
}

internal fun requestCollectorListenerRebind(context: Context) {
    if (!hasNotificationAccess(context)) return
    runCatching {
        NotificationListenerService.requestRebind(
            ComponentName(context, NfaNotificationListenerService::class.java),
        )
    }
}

internal fun hasNotificationAccess(context: Context): Boolean =
    runCatching {
        NotificationAccessStatus.isGranted(
            context,
            ComponentName(context, NfaNotificationListenerService::class.java),
        )
    }.getOrDefault(false)
