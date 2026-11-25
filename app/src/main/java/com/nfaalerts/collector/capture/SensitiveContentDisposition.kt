package com.nfaalerts.collector.capture

/**
 * Android [android.app.Notification.VISIBILITY_SECRET]. Kept as a literal so
 * unit tests do not need the Android framework.
 */
internal const val VISIBILITY_SECRET = -1

internal const val SENSITIVE_REDACTED = "SENSITIVE_REDACTED"

/**
 * Records an explicit reason when Android marked the notification sensitive and
 * the visible text fields are empty. Does not invent title or body text.
 */
internal fun classifySensitiveRedaction(
    visibility: Int?,
    lockscreenVisibilityOverride: Int?,
    channelLockscreenVisibility: Int?,
    visibleTextBlank: Boolean,
): String? {
    if (!visibleTextBlank) return null
    val markedSecret =
        visibility == VISIBILITY_SECRET ||
            lockscreenVisibilityOverride == VISIBILITY_SECRET ||
            channelLockscreenVisibility == VISIBILITY_SECRET
    return if (markedSecret) SENSITIVE_REDACTED else null
}
