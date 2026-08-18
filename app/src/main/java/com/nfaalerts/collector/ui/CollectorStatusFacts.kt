package com.nfaalerts.collector.ui

import com.nfaalerts.collector.data.CollectorStatusAggregate
import com.nfaalerts.collector.data.DeliveryState

data class CollectorStatusFacts(
    val totalCount: Long,
    val nonSentCount: Long,
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
