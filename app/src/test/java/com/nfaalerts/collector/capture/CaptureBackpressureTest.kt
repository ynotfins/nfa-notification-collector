package com.nfaalerts.collector.capture

import com.nfaalerts.collector.config.RetentionConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class CaptureBackpressureTest {
    @Test
    fun belowThresholdDoesNotPause() {
        var slept = 0L
        val pacer =
            CaptureBackpressure(
                unsentRows = { 100 },
                retention = { retention(threshold = 5_000, delayMs = 250) },
                sleeper = { slept += it },
            )

        pacer.pace()

        assertEquals(0L, slept)
    }

    @Test
    fun aboveThresholdPausesForTheConfiguredBoundedDelay() {
        var slept = 0L
        val pacer =
            CaptureBackpressure(
                unsentRows = { 5_001 },
                retention = { retention(threshold = 5_000, delayMs = 250) },
                sleeper = { slept += it },
            )

        pacer.pace()

        assertEquals(250L, slept)
    }

    @Test
    fun delayIsCappedEvenWhenConfigurationIsLarger() {
        var slept = 0L
        val pacer =
            CaptureBackpressure(
                unsentRows = { 10_000 },
                retention = { retention(threshold = 5_000, delayMs = 5_000) },
                sleeper = { slept += it },
            )

        pacer.pace()

        assertEquals(CaptureBackpressure.MAX_DELAY_MS, slept)
    }

    @Test
    fun disabledDelayNeverPausesOrRejects() {
        var slept = 0L
        val pacer =
            CaptureBackpressure(
                unsentRows = { 10_000 },
                retention = { retention(threshold = 5_000, delayMs = 0) },
                sleeper = { slept += it },
            )

        pacer.pace()

        assertEquals(0L, slept)
    }

    private fun retention(
        threshold: Int,
        delayMs: Long,
    ) = RetentionConfig(
        sentDays = 180,
        maxSentRows = 100_000,
        backpressureUnsentRows = threshold,
        backpressureDelayMs = delayMs,
    )
}
