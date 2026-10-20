package com.nfaalerts.collector.capture

/**
 * Secret-free reasons a delivered notification was not stored as a new live event.
 * Counters and diagnostics may include the package name. They must not include
 * bearer material or notification body text.
 */
enum class CaptureDropReason {
    ALLOWLIST_REJECT,
    AUTOGROUP_SUMMARY,
    COPY_CLONE_FAILURE,
    BUFFER_OVERFLOW,
    CATCH_UP_ALREADY_SAVED,
}

fun interface CaptureDropLedger {
    fun onDropped(
        reason: CaptureDropReason,
        packageName: String,
        capturePath: String,
    )
}

internal object NoOpCaptureDropLedger : CaptureDropLedger {
    override fun onDropped(
        reason: CaptureDropReason,
        packageName: String,
        capturePath: String,
    ) = Unit
}

internal fun autogroupAdmission(
    flags: Int,
    groupSummaryFlag: Int,
    localOnlyFlag: Int,
): CaptureDropReason? =
    if (isSystemAutogroupSummary(flags, groupSummaryFlag, localOnlyFlag)) {
        CaptureDropReason.AUTOGROUP_SUMMARY
    } else {
        null
    }

/**
 * Prefer a cloned notification. A failed copy is recorded and the original is
 * used only for the save that finishes before the listener callback returns.
 */
internal fun notificationAfterCopy(
    cloneSucceeded: Boolean,
    cloned: Any?,
    original: Any,
    onCloneFailure: () -> Unit,
): Any {
    if (cloneSucceeded && cloned != null) return cloned
    onCloneFailure()
    return original
}
