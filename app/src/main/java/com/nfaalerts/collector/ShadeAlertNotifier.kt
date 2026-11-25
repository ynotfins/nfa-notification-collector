package com.nfaalerts.collector

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/**
 * Operator-facing alert when a selected source is still near Android's
 * 50-notification ceiling after the shade guard ran. Only the package name and
 * the count are shown; notification content is never copied here.
 */
class ShadeAlertNotifier(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(NotificationManager::class.java)

    fun publish(alerts: Map<String, Int>) {
        val notificationManager = manager ?: return
        if (alerts.isEmpty()) {
            notificationManager.cancel(NOTIFICATION_ID)
            return
        }
        ensureChannel(notificationManager)
        val detail = alerts.entries.joinToString(", ") { "${it.key}: ${it.value}" }
        val contentIntent =
            PendingIntent.getActivity(
                appContext,
                0,
                Intent(appContext, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val notification =
            Notification
                .Builder(appContext, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_collector)
                .setContentTitle("Notification capture is at risk")
                .setContentText("Selected apps are near Android's 50-notification limit ($detail).")
                .setStyle(
                    Notification
                        .BigTextStyle()
                        .bigText(
                            "Android rejects every new post from an app once it holds 50 active " +
                                "notifications. These selected apps are still high after the shade " +
                                "guard ran: $detail. Open the collector to review capture health.",
                        ),
                ).setCategory(Notification.CATEGORY_ERROR)
                .setOnlyAlertOnce(true)
                .setAutoCancel(true)
                .setContentIntent(contentIntent)
                .setVisibility(Notification.VISIBILITY_SECRET)
                .build()
        runCatching { notificationManager.notify(NOTIFICATION_ID, notification) }
    }

    private fun ensureChannel(notificationManager: NotificationManager) {
        notificationManager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Capture risk",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Warns when a selected app is near Android's 50-notification limit."
                lockscreenVisibility = Notification.VISIBILITY_SECRET
                setShowBadge(true)
            },
        )
    }

    companion object {
        const val CHANNEL_ID = "collector-shade-alert"
        const val NOTIFICATION_ID = 2702
    }
}
