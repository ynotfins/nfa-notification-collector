package com.nfaalerts.collector.delivery

import com.nfaalerts.collector.config.EndpointProfile
import com.nfaalerts.collector.data.CapturedNotificationEntity
import com.nfaalerts.collector.data.DeliveryOutboxEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

sealed interface BearerLoad {
    data class Present(
        val value: CharArray,
    ) : BearerLoad

    data object Missing : BearerLoad

    data object TemporaryFailure : BearerLoad
}

data class RuntimeDeliverySettings(
    val endpoint: EndpointProfile,
    val relevantRevision: Long,
    val bearer: BearerLoad?,
    val deviceId: String = "nfa-primary-phone",
    val connectTimeoutMs: Long = 15_000L,
    val readTimeoutMs: Long = 30_000L,
    val initialBackoffMs: Long = 30_000L,
    val maxBackoffMs: Long = 21_600_000L,
)

fun interface DeliverySettingsProvider {
    suspend fun load(): RuntimeDeliverySettings
}

fun interface IngestTransport {
    fun send(
        settings: RuntimeDeliverySettings,
        bearer: CharArray,
        payload: WireProjectionResult.Ready,
    ): IngestResult
}

fun interface ProjectionEngine {
    fun project(
        capture: CapturedNotificationEntity,
        deviceId: String,
    ): WireProjectionResult
}

interface DeliveryScheduler {
    fun ensureScheduled(dueAtEpochMillis: Long)
}

interface DeliveryStore {
    suspend fun claimDue(
        owner: String,
        nowEpochMillis: Long,
        leaseExpiresAtEpochMillis: Long,
    ): DeliveryOutboxEntity?

    suspend fun capture(eventId: String): CapturedNotificationEntity?

    suspend fun markSent(
        eventId: String,
        owner: String,
        nowEpochMillis: Long,
        result: IngestResult.Sent,
    )

    suspend fun markRetryWait(
        eventId: String,
        owner: String,
        nowEpochMillis: Long,
        nextAttemptAtEpochMillis: Long,
        result: IngestResult.RetryWait,
    )

    suspend fun markPausedAuth(
        eventId: String,
        owner: String,
        nowEpochMillis: Long,
        configRevision: Long,
        code: String,
        httpStatus: Int?,
    )

    suspend fun markQuarantined(
        eventId: String,
        owner: String,
        nowEpochMillis: Long,
        code: String,
        httpStatus: Int?,
    )

    suspend fun nextDueAtEpochMillis(): Long?
}

class DeliveryCoordinator(
    private val store: DeliveryStore,
    private val settings: DeliverySettingsProvider,
    private val transport: IngestTransport,
    private val scheduler: DeliveryScheduler,
    private val projector: ProjectionEngine =
        ProjectionEngine {
            capture,
            deviceId,
            ->
            WireProjector().project(capture, deviceId)
        },
    private val clock: () -> Long = System::currentTimeMillis,
    private val retryPolicy: RetryPolicy = RetryPolicy(),
) {
    suspend fun drainOne(owner: String): Boolean {
        var claim: DeliveryOutboxEntity? = null
        var claimStartedAt = clock()
        var retryBounds = RetryBounds(DEFAULT_INITIAL_BACKOFF_MS, DEFAULT_MAX_BACKOFF_MS)
        try {
            claimStartedAt = clock()
            val activeClaim =
                store.claimDue(
                    owner,
                    claimStartedAt,
                    saturatingAdd(claimStartedAt, LEASE_DURATION_MS),
                ) ?: return false
            claim = activeClaim
            val capture = store.capture(activeClaim.eventId)
            if (capture == null) {
                store.markQuarantined(
                    activeClaim.eventId,
                    owner,
                    completionTime(claimStartedAt),
                    "CAPTURE_MISSING",
                    null,
                )
                return true
            }
            val runtime = settings.load()
            retryBounds = RetryBounds(runtime.initialBackoffMs, runtime.maxBackoffMs)
            when (val projection = projector.project(capture, runtime.deviceId)) {
                WireProjectionResult.BlockedContract -> {
                    store.markQuarantined(
                        claim.eventId,
                        owner,
                        completionTime(claimStartedAt),
                        "NON_BNN_CONTRACT",
                        null,
                    )
                }

                is WireProjectionResult.Quarantined -> {
                    store.markQuarantined(
                        claim.eventId,
                        owner,
                        completionTime(claimStartedAt),
                        projection.code,
                        null,
                    )
                }

                is WireProjectionResult.Ready -> {
                    deliver(owner, claimStartedAt, activeClaim, runtime, retryBounds, projection)
                }
            }
        } catch (cancelled: CancellationException) {
            claim?.let { containCancellation(owner, it, claimStartedAt) }
            throw cancelled
        } catch (_: Exception) {
            claim?.let { containFailure(owner, claimStartedAt, it, retryBounds) }
        } finally {
            scheduleNextSafely()
        }
        return true
    }

    suspend fun drainAvailable(
        owner: String,
        maximumClaims: Int = 32,
    ): Int {
        var completed = 0
        while (completed < maximumClaims && drainOne(owner)) completed++
        return completed
    }

    private suspend fun deliver(
        owner: String,
        claimStartedAt: Long,
        claim: DeliveryOutboxEntity,
        runtime: RuntimeDeliverySettings,
        retryBounds: RetryBounds,
        projection: WireProjectionResult.Ready,
    ) {
        when (val bearerState = runtime.bearer) {
            is BearerLoad.Present -> {
                val result =
                    try {
                        transport.send(runtime, bearerState.value, projection)
                    } finally {
                        bearerState.value.fill('\u0000')
                    }
                applyResult(owner, claimStartedAt, claim, runtime, retryBounds, result)
            }

            BearerLoad.TemporaryFailure -> {
                retry(
                    owner,
                    claimStartedAt,
                    claim,
                    retryBounds,
                    IngestResult.RetryWait("BEARER_TEMPORARY", null),
                )
            }

            BearerLoad.Missing,
            null,
            -> {
                store.markPausedAuth(
                    claim.eventId,
                    owner,
                    completionTime(claimStartedAt),
                    runtime.relevantRevision,
                    "MISSING_BEARER",
                    null,
                )
            }
        }
    }

    private suspend fun applyResult(
        owner: String,
        claimStartedAt: Long,
        claim: DeliveryOutboxEntity,
        runtime: RuntimeDeliverySettings,
        retryBounds: RetryBounds,
        result: IngestResult,
    ) {
        when (result) {
            is IngestResult.Sent -> {
                store.markSent(claim.eventId, owner, completionTime(claimStartedAt), result)
            }

            is IngestResult.RetryWait -> {
                retry(owner, claimStartedAt, claim, retryBounds, result)
            }

            is IngestResult.PausedAuth -> {
                store.markPausedAuth(
                    claim.eventId,
                    owner,
                    completionTime(claimStartedAt),
                    runtime.relevantRevision,
                    result.code,
                    result.httpStatus,
                )
            }

            is IngestResult.Quarantined -> {
                store.markQuarantined(
                    claim.eventId,
                    owner,
                    completionTime(claimStartedAt),
                    result.code,
                    result.httpStatus,
                )
            }
        }
    }

    private suspend fun retry(
        owner: String,
        claimStartedAt: Long,
        claim: DeliveryOutboxEntity,
        retryBounds: RetryBounds,
        result: IngestResult.RetryWait,
    ) {
        val completedAt = completionTime(claimStartedAt)
        val next =
            saturatingAdd(
                completedAt,
                retryPolicy.delayMillis(
                    attemptCount = claim.attemptCount.coerceAtLeast(1),
                    initialDelayMs = retryBounds.initialMs,
                    maximumDelayMs = retryBounds.maximumMs,
                ),
            )
        store.markRetryWait(claim.eventId, owner, completedAt, next, result)
        scheduler.ensureScheduled(next)
    }

    private suspend fun containFailure(
        owner: String,
        claimStartedAt: Long,
        claim: DeliveryOutboxEntity,
        retryBounds: RetryBounds,
    ) {
        try {
            retry(
                owner,
                claimStartedAt,
                claim,
                retryBounds,
                IngestResult.RetryWait("INTERNAL_DELIVERY_ERROR", null),
            )
        } catch (cancelled: CancellationException) {
            containCancellation(owner, claim, claimStartedAt)
            throw cancelled
        } catch (_: Exception) {
            scheduleLeaseRecovery(claim, claimStartedAt)
        }
    }

    private fun completionTime(fallback: Long): Long = runCatching(clock).getOrDefault(fallback)

    private fun scheduleLeaseRecovery(
        claim: DeliveryOutboxEntity,
        claimStartedAt: Long,
    ) {
        val leaseExpiry = claim.leaseExpiresAtEpochMillis ?: saturatingAdd(claimStartedAt, LEASE_DURATION_MS)
        runCatching { scheduler.ensureScheduled(leaseExpiry) }
    }

    private suspend fun containCancellation(
        owner: String,
        claim: DeliveryOutboxEntity,
        claimStartedAt: Long,
    ) {
        withContext(NonCancellable) {
            val completedAt = completionTime(claimStartedAt)
            val leaseExpiry =
                (
                    claim.leaseExpiresAtEpochMillis
                        ?: saturatingAdd(claimStartedAt, LEASE_DURATION_MS)
                ).coerceAtLeast(completedAt)
            try {
                store.markRetryWait(
                    claim.eventId,
                    owner,
                    completedAt,
                    leaseExpiry,
                    IngestResult.RetryWait("WORKER_CANCELLED", null),
                )
                runCatching { scheduler.ensureScheduled(leaseExpiry) }
            } catch (_: Exception) {
                scheduleLeaseRecovery(claim, claimStartedAt)
            }
        }
    }

    private suspend fun scheduleNextSafely() {
        runCatching { store.nextDueAtEpochMillis() }
            .getOrNull()
            ?.let { runCatching { scheduler.ensureScheduled(it) } }
    }

    private fun saturatingAdd(
        left: Long,
        right: Long,
    ): Long = if (right > 0L && left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

    private data class RetryBounds(
        val initialMs: Long,
        val maximumMs: Long,
    )

    companion object {
        const val LEASE_DURATION_MS = 600_000L
        private const val DEFAULT_INITIAL_BACKOFF_MS = 30_000L
        private const val DEFAULT_MAX_BACKOFF_MS = 21_600_000L
    }
}

class RoomDeliveryStore(
    private val database: com.nfaalerts.collector.data.NfaCollectorDatabase,
) : DeliveryStore {
    override suspend fun claimDue(
        owner: String,
        nowEpochMillis: Long,
        leaseExpiresAtEpochMillis: Long,
    ) = database.deliveryDao().claimDue(owner, nowEpochMillis, leaseExpiresAtEpochMillis)

    override suspend fun capture(eventId: String) = database.captureReadDao().capture(eventId)

    override suspend fun markSent(
        eventId: String,
        owner: String,
        nowEpochMillis: Long,
        result: IngestResult.Sent,
    ) {
        check(database.deliveryDao().markSent(eventId, owner, nowEpochMillis, result.ingestId, result.receivedAt) == 1)
    }

    override suspend fun markRetryWait(
        eventId: String,
        owner: String,
        nowEpochMillis: Long,
        nextAttemptAtEpochMillis: Long,
        result: IngestResult.RetryWait,
    ) {
        check(
            database.deliveryDao().markRetryWait(
                eventId,
                owner,
                nowEpochMillis,
                nextAttemptAtEpochMillis,
                result.httpStatus,
                result.code,
            ) == 1,
        )
    }

    override suspend fun markPausedAuth(
        eventId: String,
        owner: String,
        nowEpochMillis: Long,
        configRevision: Long,
        code: String,
        httpStatus: Int?,
    ) {
        check(
            database.deliveryDao().markPausedAuth(
                eventId,
                owner,
                nowEpochMillis,
                configRevision,
                httpStatus,
                code,
            ) == 1,
        )
    }

    override suspend fun markQuarantined(
        eventId: String,
        owner: String,
        nowEpochMillis: Long,
        code: String,
        httpStatus: Int?,
    ) {
        check(database.deliveryDao().markQuarantined(eventId, owner, nowEpochMillis, httpStatus, code) == 1)
    }

    override suspend fun nextDueAtEpochMillis(): Long? = database.deliveryDao().nextDueAtEpochMillis()
}
