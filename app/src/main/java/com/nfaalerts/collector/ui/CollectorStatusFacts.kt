package com.nfaalerts.collector.ui

import com.nfaalerts.collector.data.CollectorStatusAggregate
import com.nfaalerts.collector.data.DeliveryState
import java.util.Locale

data class CollectorStatusFacts(
    val totalCount: Long,
    val nonSentCount: Long,
    val sendableCount: Long,
    val heldBlockedCount: Long,
    val heldQuarantinedCount: Long,
    val countsByState: Map<DeliveryState, Long>,
    val lastCaptureAtEpochMillis: Long?,
    val lastSentAtEpochMillis: Long?,
    val lastServerReceivedAt: String?,
    val latestSafeError: String?,
) {
    companion object {
        fun from(aggregate: CollectorStatusAggregate) =
            CollectorStatusFacts(
                totalCount = aggregate.totalCount,
                nonSentCount = aggregate.nonSentCount,
                sendableCount =
                    aggregate.pendingCount +
                        aggregate.sendingCount +
                        aggregate.retryWaitCount +
                        aggregate.pausedAuthCount,
                heldBlockedCount = aggregate.blockedContractCount,
                heldQuarantinedCount = aggregate.quarantinedCount,
                countsByState =
                    mapOf(
                        DeliveryState.PENDING to aggregate.pendingCount,
                        DeliveryState.SENDING to aggregate.sendingCount,
                        DeliveryState.RETRY_WAIT to aggregate.retryWaitCount,
                        DeliveryState.PAUSED_AUTH to aggregate.pausedAuthCount,
                        DeliveryState.BLOCKED_CONTRACT to aggregate.blockedContractCount,
                        DeliveryState.QUARANTINED to aggregate.quarantinedCount,
                        DeliveryState.SENT to aggregate.sentCount,
                    ),
                lastCaptureAtEpochMillis = aggregate.lastCaptureAtEpochMillis,
                lastSentAtEpochMillis = aggregate.lastSentAtEpochMillis,
                lastServerReceivedAt = aggregate.lastServerReceivedAt,
                latestSafeError = aggregate.latestSafeError,
            )
    }
}

fun formatStorageBytes(bytes: Long): String =
    when {
        bytes >= GIB -> String.format(Locale.US, "%.1f GB", bytes / GIB.toDouble())
        bytes >= MIB -> String.format(Locale.US, "%.1f MB", bytes / MIB.toDouble())
        bytes >= KIB -> String.format(Locale.US, "%.1f KB", bytes / KIB.toDouble())
        else -> "$bytes B"
    }

private const val KIB = 1024L
private const val MIB = 1024L * KIB
private const val GIB = 1024L * MIB
