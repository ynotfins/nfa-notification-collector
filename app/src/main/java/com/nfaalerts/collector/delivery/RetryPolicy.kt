package com.nfaalerts.collector.delivery

import kotlin.math.pow
import kotlin.random.Random

class RetryPolicy(
    private val initialDelayMs: Long = 30_000L,
    private val maximumDelayMs: Long = 21_600_000L,
    private val jitter: () -> Double = { Random.nextDouble() },
) {
    fun delayMillis(attemptCount: Int): Long = delayMillis(attemptCount, initialDelayMs, maximumDelayMs)

    fun delayMillis(
        attemptCount: Int,
        initialDelayMs: Long,
        maximumDelayMs: Long,
    ): Long {
        require(attemptCount >= 1) { "Attempt count must be positive." }
        require(initialDelayMs > 0L) { "Initial delay must be positive." }
        require(maximumDelayMs >= initialDelayMs) { "Maximum delay must not be less than initial delay." }
        val sample = jitter()
        require(sample in 0.0..1.0) { "Jitter must be in [0,1]." }
        val exponent = (attemptCount - 1).coerceAtMost(30)
        val raw = (initialDelayMs.toDouble() * 2.0.pow(exponent)).toLong().coerceAtMost(maximumDelayMs)
        val half = raw / 2L
        return (half + (half * sample).toLong()).coerceAtMost(maximumDelayMs)
    }
}
