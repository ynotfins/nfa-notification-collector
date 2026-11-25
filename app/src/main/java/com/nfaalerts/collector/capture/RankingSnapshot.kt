package com.nfaalerts.collector.capture

/**
 * Diagnostics copied from [android.service.notification.NotificationListenerService.Ranking].
 * No notification text is stored here.
 */
data class RankingSnapshot(
    val importance: Int? = null,
    val suppressedVisualEffects: Int? = null,
    val matchesInterruptionFilter: Boolean? = null,
    val ambient: Boolean? = null,
    val lastAudibleAlertAtEpochMillis: Long? = null,
    val channelId: String? = null,
    val lockscreenVisibilityOverride: Int? = null,
    val channelLockscreenVisibility: Int? = null,
    val suspended: Boolean? = null,
)
