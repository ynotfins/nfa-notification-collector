package com.nfaalerts.collector.capture

import com.nfaalerts.collector.config.RetentionConfig

fun interface CapturePacer {
    fun pace()
}

object NoCapturePacing : CapturePacer {
    override fun pace() = Unit
}

/**
 * Adds a bounded pause before persisting a capture when the outbox already has a large unsent
 * backlog. It never rejects a notification and never waits longer than [MAX_DELAY_MS], so a burst
 * can still drain through shade catch-up instead of being discarded.
 */
class CaptureBackpressure(
    private val unsentRows: () -> Long,
    private val retention: () -> RetentionConfig,
    private val sleeper: (Long) -> Unit = { millis -> Thread.sleep(millis) },
) : CapturePacer {
    override fun pace() {
        val config = retention()
        if (unsentRows() <= config.backpressureUnsentRows) return
        val delayMillis = config.backpressureDelayMs.coerceIn(0L, MAX_DELAY_MS)
        if (delayMillis > 0L) sleeper(delayMillis)
    }

    companion object {
        const val MAX_DELAY_MS = 1_000L
    }
}
