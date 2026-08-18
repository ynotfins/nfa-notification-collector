package com.nfaalerts.collector.ui

import com.nfaalerts.collector.data.DeliveryState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeliveryRetryEligibilityTest {
    @Test
    fun `only transient retry wait rows are manually retryable`() {
        assertTrue(DeliveryRetryEligibility.allows(DeliveryState.RETRY_WAIT))
        DeliveryState.entries.filterNot { it == DeliveryState.RETRY_WAIT }.forEach {
            assertFalse("$it must not be manually reset", DeliveryRetryEligibility.allows(it))
        }
    }
}
