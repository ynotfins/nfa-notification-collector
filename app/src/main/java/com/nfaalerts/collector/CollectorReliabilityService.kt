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
import android.os.IBinder
import android.service.notification.NotificationListenerService
import com.nfaalerts.collector.capture.NfaNotificationListenerService
import com.nfaalerts.collector.capture.NotificationAccessStatus

class CollectorReliabilityService : Service() {
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
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        if (!hasNotificationAccess(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        requestCollectorListenerRebind(this)
        (application as NfaCollectorApp).appContainer.onConnectivityAvailable()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification(): Notification {
        val contentIntent =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        return Notification
            .Builder(this, CHANNEL_ID)
            .setSmallIcon(com.nfaalerts.collector.R.drawable.ic_collector)
            .setContentTitle("NFA Collector is protecting capture")
            .setContentText("Notifications are persisted locally before ordered delivery.")
            .setContentIntent(contentIntent)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_SECRET)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "collector-reliability"
        private const val NOTIFICATION_ID = 2701

        fun update(context: Context) {
            val intent = Intent(context, CollectorReliabilityService::class.java)
            if (hasNotificationAccess(context)) {
                runCatching { context.startForegroundService(intent) }
            } else {
                context.stopService(intent)
            }
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
