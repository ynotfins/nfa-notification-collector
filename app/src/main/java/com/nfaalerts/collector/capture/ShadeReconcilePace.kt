package com.nfaalerts.collector.capture

object CapturePath {
    const val LIVE = "LIVE"
    const val SHADE = "SHADE"
    const val UNKNOWN = "UNKNOWN"
}

/**
 * Live [android.service.notification.NotificationListenerService.onNotificationPosted]
 * stays immediate. While the listener is connected and the network is down, shade
 * reads run every few seconds. A disconnected listener cannot read the shade; the
 * reliability service asks for a reconnect on that same interval, then one shade
 * read runs on reconnect and when the network returns.
 */
object ShadeReconcilePace {
    const val DEGRADED_INTERVAL_MS = 3_000L

    fun degraded(
        listenerConnected: Boolean,
        networkAvailable: Boolean,
    ): Boolean = !listenerConnected || !networkAvailable
}
