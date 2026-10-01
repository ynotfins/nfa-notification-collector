package com.nfaalerts.collector.delivery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

class DrainPaceLimiterTest {
    @Test
    fun `burst allows immediate sends then waits for refill`() {
        val now = AtomicLong(1_000_000L)
        val sleeps = mutableListOf<Long>()
        val limiter =
            DrainPaceLimiter(
                burst = 3,
                refillPerMinute = 60,
                clock = { now.get() },
                sleeper = { sleeps += it },
            )
        repeat(3) { limiter.beforeSend() }
        assertTrue(sleeps.isEmpty())
        limiter.beforeSend()
        assertEquals(1, sleeps.size)
        assertTrue(sleeps.first() >= 1L)
    }
}
