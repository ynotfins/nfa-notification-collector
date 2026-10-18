package com.nfaalerts.collector.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShadeReconcilePaceTest {
    @Test
    fun degradedShadeCheckIsAFewSecondsAndIdleWhenLivePathIsHealthy() {
        assertEquals(3_000L, ShadeReconcilePace.DEGRADED_INTERVAL_MS)
        assertTrue(ShadeReconcilePace.DEGRADED_INTERVAL_MS <= 5_000L)
        assertTrue(ShadeReconcilePace.degraded(listenerConnected = false, networkAvailable = true))
        assertTrue(ShadeReconcilePace.degraded(listenerConnected = true, networkAvailable = false))
        assertFalse(ShadeReconcilePace.degraded(listenerConnected = true, networkAvailable = true))
    }
}
