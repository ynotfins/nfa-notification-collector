package com.nfaalerts.collector.capture

object CapturePath {
    const val LIVE = "LIVE"
    const val SHADE = "SHADE"
    const val UNKNOWN = "UNKNOWN"
}

/**
 * Live [android.service.notification.NotificationListenerService.onNotificationPosted]
 * stays immediate. Shade reads run only while the listener is disconnected or the
 * network is down, and then every few seconds so a still-posted alert is not left
 * for a minute.
 */
object ShadeReconcilePace {
    const val DEGRADED_INTERVAL_MS = 3_000L

    fun degraded(
        listenerConnected: Boolean,
        networkAvailable: Boolean,
    ): Boolean = !listenerConnected || !networkAvailable
}
