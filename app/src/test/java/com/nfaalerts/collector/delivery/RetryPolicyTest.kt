package com.nfaalerts.collector.delivery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RetryPolicyTest {
    @Test
    fun exponentialDelayStartsAtThirtySecondsCapsAtSixHoursAndHasNoAttemptLimit() {
        val minimum = RetryPolicy { 0.0 }
        val maximum = RetryPolicy { 1.0 }

        assertEquals(15_000L, minimum.delayMillis(1))
        assertEquals(30_000L, maximum.delayMillis(1))
        assertEquals(30_000L, minimum.delayMillis(2))
        assertEquals(60_000L, maximum.delayMillis(2))
        assertTrue(minimum.delayMillis(10_000) in 10_800_000L..21_600_000L)
        assertEquals(21_600_000L, maximum.delayMillis(10_000))
    }

    @Test(expected = IllegalArgumentException::class)
    fun jitterBelowRangeIsRejected() {
        RetryPolicy { -0.1 }.delayMillis(1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun jitterAboveRangeIsRejected() {
        RetryPolicy { 1.1 }.delayMillis(1)
    }
}
