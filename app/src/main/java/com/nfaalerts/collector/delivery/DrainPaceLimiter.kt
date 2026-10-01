package com.nfaalerts.collector.delivery

/**
 * Paces outbox drains to the published ingest rate envelope:
 * authenticated burst ~240, refill ~120/minute/device.
 * Does not skip older retry rows; it only inserts delays between sends.
 */
class DrainPaceLimiter(
    private val burst: Int = DEFAULT_BURST,
    private val refillPerMinute: Int = DEFAULT_REFILL_PER_MINUTE,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleeper: (Long) -> Unit = { millis -> Thread.sleep(millis) },
) {
    private var tokens = burst.toDouble()
    private var lastRefillAt = clock()

    @Synchronized
    fun beforeSend() {
        refill()
        if (tokens >= 1.0) {
            tokens -= 1.0
            return
        }
        val waitMs = ((1.0 - tokens) / refillPerSecond() * 1000.0).toLong().coerceAtLeast(1L)
        sleeper(waitMs)
        refill()
        tokens = (tokens - 1.0).coerceAtLeast(0.0)
    }

    private fun refill() {
        val now = clock()
        val elapsedMs = (now - lastRefillAt).coerceAtLeast(0L)
        if (elapsedMs == 0L) return
        tokens = (tokens + elapsedMs / 1000.0 * refillPerSecond()).coerceAtMost(burst.toDouble())
        lastRefillAt = now
    }

    private fun refillPerSecond(): Double = refillPerMinute / 60.0

    companion object {
        const val DEFAULT_BURST = 240
        const val DEFAULT_REFILL_PER_MINUTE = 120
    }
}
