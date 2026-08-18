package com.nfaalerts.collector.delivery

import com.nfaalerts.collector.config.EndpointProfile
import com.nfaalerts.collector.data.CapturedNotificationEntity
import com.nfaalerts.collector.data.DeliveryOutboxEntity

sealed interface BearerLoad {
    data class Present(
        val value: CharArray,
    ) : BearerLoad

    data object Missing : BearerLoad
}

data class RuntimeDeliverySettings(
    val endpoint: EndpointProfile,
    val relevantRevision: Long,
    val bearer: BearerLoad?,
    val deviceId: String = "nfa-primary-phone",
    val connectTimeoutMs: Long = 15_000L,
    val readTimeoutMs: Long = 30_000L,
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
    private val projector: WireProjector = WireProjector(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val retryPolicy: RetryPolicy = RetryPolicy(),
) {
    suspend fun drainOne(owner: String): Boolean {
        val now = clock()
        val claim = store.claimDue(owner, now, now + LEASE_DURATION_MS) ?: return false
        val capture = store.capture(claim.eventId)
        if (capture == null) {
            store.markQuarantined(claim.eventId, owner, now, "CAPTURE_MISSING", null)
            scheduleNext()
            return true
        }
        val runtime = settings.load()
        when (val projection = projector.project(capture, runtime.deviceId)) {
            WireProjectionResult.BlockedContract -> {
                store.markQuarantined(claim.eventId, owner, now, "NON_BNN_CONTRACT", null)
            }

            is WireProjectionResult.Quarantined -> {
                store.markQuarantined(claim.eventId, owner, now, projection.code, null)
            }

            is WireProjectionResult.Ready -> {
                deliver(owner, now, claim, runtime, projection)
            }
        }
        scheduleNext()
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
        now: Long,
        claim: DeliveryOutboxEntity,
        runtime: RuntimeDeliverySettings,
        projection: WireProjectionResult.Ready,
    ) {
        val bearer = (runtime.bearer as? BearerLoad.Present)?.value
        if (bearer == null || bearer.isEmpty()) {
            bearer?.fill('\u0000')
            store.markPausedAuth(
                claim.eventId,
                owner,
                now,
                runtime.relevantRevision,
                "MISSING_BEARER",
                null,
            )
            return
        }
        val result =
            try {
                transport.send(runtime, bearer, projection)
            } finally {
                bearer.fill('\u0000')
            }
        when (result) {
            is IngestResult.Sent -> {
                store.markSent(claim.eventId, owner, now, result)
            }

            is IngestResult.RetryWait -> {
                val next = now + retryPolicy.delayMillis(claim.attemptCount.coerceAtLeast(1))
                store.markRetryWait(claim.eventId, owner, now, next, result)
                scheduler.ensureScheduled(next)
            }

            is IngestResult.PausedAuth -> {
                store.markPausedAuth(
                    claim.eventId,
                    owner,
                    now,
                    runtime.relevantRevision,
                    result.code,
                    result.httpStatus,
                )
            }

            is IngestResult.Quarantined -> {
                store.markQuarantined(claim.eventId, owner, now, result.code, result.httpStatus)
            }
        }
    }

    private suspend fun scheduleNext() {
        store.nextDueAtEpochMillis()?.let(scheduler::ensureScheduled)
    }

    companion object {
        const val LEASE_DURATION_MS = 600_000L
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
