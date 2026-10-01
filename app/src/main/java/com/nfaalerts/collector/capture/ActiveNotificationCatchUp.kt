package com.nfaalerts.collector.capture

/**
 * Catch-up for notifications that may have been posted while the listener was
 * disconnected. Live [PostedNotificationCallback.onNotificationPosted] remains
 * the primary path and still treats every callback as a distinct event.
 *
 * Catch-up skips identities already persisted for the same package/key/postTime
 * so reconnect sweeps do not flood the outbox with synthetic copies.
 */
class ActiveNotificationCatchUp(
    private val alreadyCaptured: suspend (packageName: String, key: String, postTimeEpochMillis: Long) -> Boolean,
    private val onMissing: (LightweightPostedNotification) -> Boolean,
    private val diagnostics: CatchUpDiagnostics = NoOpCatchUpDiagnostics,
) {
    suspend fun reconcile(notifications: List<LightweightPostedNotification>): CatchUpResult {
        var examined = 0
        var imported = 0
        var skippedExisting = 0
        var skippedUnselected = 0
        for (notification in notifications) {
            examined++
            if (alreadyCaptured(notification.packageName, notification.key, notification.postTimeEpochMillis)) {
                skippedExisting++
                continue
            }
            val accepted = onMissing(notification)
            if (accepted) {
                imported++
            } else {
                skippedUnselected++
            }
        }
        val result = CatchUpResult(examined, imported, skippedExisting, skippedUnselected)
        diagnostics.onCatchUpFinished(result)
        return result
    }
}

data class CatchUpResult(
    val examined: Int,
    val imported: Int,
    val skippedExisting: Int,
    val skippedUnselected: Int,
)

fun interface CatchUpDiagnostics {
    fun onCatchUpFinished(result: CatchUpResult)
}

private object NoOpCatchUpDiagnostics : CatchUpDiagnostics {
    override fun onCatchUpFinished(result: CatchUpResult) = Unit
}

fun isSystemAutogroupSummary(
    flags: Int,
    groupSummaryFlag: Int,
    localOnlyFlag: Int,
): Boolean = flags and groupSummaryFlag != 0 && flags and localOnlyFlag != 0
