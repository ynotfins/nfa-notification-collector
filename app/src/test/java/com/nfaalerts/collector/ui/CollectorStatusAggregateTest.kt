package com.nfaalerts.collector.ui

import com.nfaalerts.collector.data.CollectorStatusAggregate
import com.nfaalerts.collector.data.DeliveryState
import org.junit.Assert.assertEquals
import org.junit.Test

class CollectorStatusAggregateTest {
    @Test
    fun `status facts retain exact counts beyond the recent delivery window`() {
        val aggregate =
            CollectorStatusAggregate(
                totalCount = 205,
                nonSentCount = 105,
                pendingCount = 101,
                sendingCount = 1,
                retryWaitCount = 1,
                pausedAuthCount = 1,
                blockedContractCount = 1,
                quarantinedCount = 0,
                sentCount = 100,
                lastCaptureAtEpochMillis = 900,
                lastSentAtEpochMillis = 800,
                lastServerReceivedAt = "2026-08-18T12:00:00Z",
                latestSafeError = "HTTP_503",
            )

        val facts = CollectorStatusFacts.from(aggregate)

        assertEquals(205L, facts.totalCount)
        assertEquals(105L, facts.nonSentCount)
        assertEquals(101L, facts.countsByState.getValue(DeliveryState.PENDING))
        assertEquals(100L, facts.countsByState.getValue(DeliveryState.SENT))
        assertEquals(900L, facts.lastCaptureAtEpochMillis)
        assertEquals("2026-08-18T12:00:00Z", facts.lastServerReceivedAt)
        assertEquals("HTTP_503", facts.latestSafeError)
    }
}
