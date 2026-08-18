package com.nfaalerts.collector.delivery

import com.nfaalerts.collector.config.EndpointProfile
import com.nfaalerts.collector.data.CapturedNotificationEntity
import com.nfaalerts.collector.data.DeliveryOutboxEntity
import com.nfaalerts.collector.data.DeliveryState
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DeliveryCoordinatorTest {
    @Test
    fun missingBearerPausesClaimWithoutCallingTransport() =
        kotlinx.coroutines.runBlocking {
            val store = FakeDeliveryStore(capture())
            var sends = 0
            val coordinator =
                DeliveryCoordinator(
                    store = store,
                    settings = {
                        RuntimeDeliverySettings(
                            EndpointProfile("https://example.invalid", "/v1/ingest/alerts"),
                            9L,
                            null,
                        )
                    },
                    transport = { _, _, _ ->
                        sends += 1
                        error("must not send")
                    },
                    scheduler = store,
                    clock = { 1_000L },
                    retryPolicy = RetryPolicy { 1.0 },
                )

            assertTrue(coordinator.drainOne("immediate"))
            assertEquals(0, sends)
            assertEquals("PAUSED_AUTH:9:MISSING_BEARER", store.transition)
        }

    @Test
    fun transientAttemptUsesDatabaseNextAttemptAndSchedulesWithoutMaxAttempt() =
        kotlinx.coroutines.runBlocking {
            val store = FakeDeliveryStore(capture(), attemptCount = 10_000)
            val coordinator =
                DeliveryCoordinator(
                    store = store,
                    settings = {
                        RuntimeDeliverySettings(
                            EndpointProfile("https://example.invalid", "/v1/ingest/alerts"),
                            11L,
                            BearerLoad.Present(CharArray(43) { 'x' }),
                        )
                    },
                    transport = { _, bearer, _ ->
                        bearer.fill('\u0000')
                        IngestResult.RetryWait("NETWORK", null)
                    },
                    scheduler = store,
                    clock = { 1_000L },
                    retryPolicy = RetryPolicy { 1.0 },
                )

            assertTrue(coordinator.drainOne("worker"))
            assertEquals("RETRY_WAIT:1000:21601000:NETWORK", store.transition)
            assertEquals(21_601_000L, store.scheduledAt)
        }

    @Test
    fun retryDelayUsesFailureCompletionClockAndRuntimeBounds() =
        kotlinx.coroutines.runBlocking {
            val times = ArrayDeque(listOf(1_000L, 61_000L))
            val store = FakeDeliveryStore(capture(), attemptCount = 2)
            val coordinator =
                DeliveryCoordinator(
                    store = store,
                    settings = {
                        RuntimeDeliverySettings(
                            endpoint = EndpointProfile("https://example.invalid", "/v1/ingest/alerts"),
                            relevantRevision = 1L,
                            bearer = BearerLoad.Present(CharArray(43) { 'x' }),
                            initialBackoffMs = 10_000L,
                            maxBackoffMs = 20_000L,
                        )
                    },
                    transport = { _, bearer, _ ->
                        bearer.fill('\u0000')
                        IngestResult.RetryWait("TIMEOUT", null)
                    },
                    scheduler = store,
                    clock = { times.removeFirst() },
                    retryPolicy = RetryPolicy { 1.0 },
                )

            coordinator.drainOne("worker")

            assertEquals("RETRY_WAIT:61000:81000:TIMEOUT", store.transition)
            assertEquals(81_000L, store.scheduledAt)
        }

    @Test
    fun temporaryBearerFailureRetriesWithoutTransportOrAuthPause() =
        kotlinx.coroutines.runBlocking {
            val times = ArrayDeque(listOf(1_000L, 2_000L))
            val store = FakeDeliveryStore(capture())
            var sends = 0
            val coordinator =
                DeliveryCoordinator(
                    store = store,
                    settings = {
                        RuntimeDeliverySettings(
                            EndpointProfile("https://example.invalid", "/v1/ingest/alerts"),
                            1L,
                            BearerLoad.TemporaryFailure,
                        )
                    },
                    transport = { _, _, _ ->
                        sends += 1
                        error("must not send")
                    },
                    scheduler = store,
                    clock = { times.removeFirst() },
                    retryPolicy = RetryPolicy { 1.0 },
                )

            coordinator.drainOne("worker")

            assertEquals(0, sends)
            assertEquals("RETRY_WAIT:2000:32000:BEARER_TEMPORARY", store.transition)
        }

    @Test
    fun settingsProjectionAndTransportExceptionsBecomeLegalRetries() =
        kotlinx.coroutines.runBlocking {
            ExceptionStage.entries.forEach { stage ->
                val times = ArrayDeque(listOf(1_000L, 2_000L))
                val store = FakeDeliveryStore(capture(), throwOnCapture = stage == ExceptionStage.CAPTURE)
                val coordinator =
                    DeliveryCoordinator(
                        store = store,
                        settings = {
                            if (stage == ExceptionStage.SETTINGS) error("settings")
                            RuntimeDeliverySettings(
                                EndpointProfile("https://example.invalid", "/v1/ingest/alerts"),
                                1L,
                                BearerLoad.Present(CharArray(43) { 'x' }),
                            )
                        },
                        transport = { _, bearer, _ ->
                            bearer.fill('\u0000')
                            if (stage == ExceptionStage.TRANSPORT) error("transport")
                            IngestResult.RetryWait("NETWORK", null)
                        },
                        scheduler = store,
                        projector =
                            ProjectionEngine { capture, deviceId ->
                                if (stage == ExceptionStage.PROJECTION) error("projection")
                                WireProjector().project(capture, deviceId)
                            },
                        clock = { times.removeFirst() },
                        retryPolicy = RetryPolicy { 1.0 },
                    )

                assertTrue("stage=$stage", coordinator.drainOne("worker"))
                assertTrue(
                    "stage=$stage ${store.transition}",
                    store.transition.orEmpty().contains("INTERNAL_DELIVERY_ERROR"),
                )
            }
        }

    @Test
    fun failedContainmentTransitionSchedulesLeaseExpiryRecovery() =
        kotlinx.coroutines.runBlocking {
            val store = FakeDeliveryStore(capture(), throwOnRetry = true)
            val coordinator =
                DeliveryCoordinator(
                    store = store,
                    settings = { error("settings") },
                    transport = { _, _, _ -> error("transport") },
                    scheduler = store,
                    clock = { 1_000L },
                )

            assertTrue(coordinator.drainOne("worker"))
            assertEquals(601_000L, store.scheduledAt)
        }

    @Test
    fun cancellationSchedulesLeaseExpiryAndRemainsCancellationAware() =
        kotlinx.coroutines.runBlocking {
            val store = FakeDeliveryStore(capture())
            val coordinator =
                DeliveryCoordinator(
                    store = store,
                    settings = {
                        RuntimeDeliverySettings(
                            EndpointProfile("https://example.invalid", "/v1/ingest/alerts"),
                            1L,
                            BearerLoad.Present(CharArray(43) { 'x' }),
                        )
                    },
                    transport = { _, _, _ -> throw CancellationException("cancel") },
                    scheduler = store,
                    clock = { 1_000L },
                )

            try {
                coordinator.drainOne("worker")
                fail("CancellationException expected")
            } catch (_: CancellationException) {
                assertEquals(601_000L, store.scheduledAt)
                assertEquals("RETRY_WAIT:1000:601000:WORKER_CANCELLED", store.transition)
            }
        }

    @Test
    fun nonBnnNeverReachesTransportEvenIfDatabaseStateIsCorrupt() =
        kotlinx.coroutines.runBlocking {
            val store = FakeDeliveryStore(capture(sourceId = "weather"))
            var sends = 0
            val coordinator =
                DeliveryCoordinator(
                    store = store,
                    settings = {
                        RuntimeDeliverySettings(
                            EndpointProfile("https://example.invalid", "/v1/ingest/alerts"),
                            1L,
                            BearerLoad.Present(CharArray(43) { 'x' }),
                        )
                    },
                    transport = { _, _, _ ->
                        sends += 1
                        IngestResult.RetryWait("NETWORK", null)
                    },
                    scheduler = store,
                    clock = { 1_000L },
                )

            coordinator.drainOne("worker")

            assertEquals(0, sends)
            assertEquals("QUARANTINED:NON_BNN_CONTRACT", store.transition)
        }

    @Test
    fun configuredDeviceIdIsUsedForTheClaimedPayload() =
        kotlinx.coroutines.runBlocking {
            val store = FakeDeliveryStore(capture())
            var observedDeviceId: String? = null
            val coordinator =
                DeliveryCoordinator(
                    store = store,
                    settings = {
                        RuntimeDeliverySettings(
                            endpoint = EndpointProfile("https://example.invalid", "/v1/ingest/alerts"),
                            relevantRevision = 2L,
                            bearer = BearerLoad.Present(CharArray(43) { 'x' }),
                            deviceId = "configured-phone",
                        )
                    },
                    transport = { _, bearer, payload ->
                        observedDeviceId =
                            kotlinx.serialization.json.Json
                                .parseToJsonElement(payload.bodyBytes.decodeToString())
                                .jsonObject
                                .getValue("deviceId")
                                .jsonPrimitive
                                .content
                        bearer.fill('\u0000')
                        IngestResult.Sent("123e4567-e89b-12d3-a456-426614174000", "2026-08-18T12:00:00Z")
                    },
                    scheduler = store,
                    clock = { 1_000L },
                )

            coordinator.drainOne("worker")

            assertEquals("configured-phone", observedDeviceId)
        }

    private class FakeDeliveryStore(
        private val capture: CapturedNotificationEntity,
        private val attemptCount: Int = 1,
        private val throwOnRetry: Boolean = false,
        private val throwOnCapture: Boolean = false,
    ) : DeliveryStore,
        DeliveryScheduler {
        var transition: String? = null
        var scheduledAt: Long? = null

        override suspend fun claimDue(
            owner: String,
            nowEpochMillis: Long,
            leaseExpiresAtEpochMillis: Long,
        ) = DeliveryOutboxEntity(
            eventId = capture.eventId,
            state = DeliveryState.SENDING,
            attemptCount = attemptCount,
            leaseOwner = owner,
            leaseExpiresAtEpochMillis = leaseExpiresAtEpochMillis,
            createdAtEpochMillis = 0,
            updatedAtEpochMillis = nowEpochMillis,
        )

        override suspend fun capture(eventId: String): CapturedNotificationEntity {
            if (throwOnCapture) error("capture")
            return capture
        }

        override suspend fun markSent(
            eventId: String,
            owner: String,
            nowEpochMillis: Long,
            result: IngestResult.Sent,
        ) {
            transition = "SENT"
        }

        override suspend fun markRetryWait(
            eventId: String,
            owner: String,
            nowEpochMillis: Long,
            nextAttemptAtEpochMillis: Long,
            result: IngestResult.RetryWait,
        ) {
            if (throwOnRetry) error("transition")
            transition = "RETRY_WAIT:$nowEpochMillis:$nextAttemptAtEpochMillis:${result.code}"
        }

        override suspend fun markPausedAuth(
            eventId: String,
            owner: String,
            nowEpochMillis: Long,
            configRevision: Long,
            code: String,
            httpStatus: Int?,
        ) {
            transition = "PAUSED_AUTH:$configRevision:$code"
        }

        override suspend fun markQuarantined(
            eventId: String,
            owner: String,
            nowEpochMillis: Long,
            code: String,
            httpStatus: Int?,
        ) {
            transition = "QUARANTINED:$code"
        }

        override suspend fun nextDueAtEpochMillis(): Long? = scheduledAt

        override fun ensureScheduled(dueAtEpochMillis: Long) {
            scheduledAt = dueAtEpochMillis
        }
    }

    private enum class ExceptionStage {
        CAPTURE,
        SETTINGS,
        PROJECTION,
        TRANSPORT,
    }

    private fun capture(sourceId: String = "bnn") =
        CapturedNotificationEntity(
            eventId = "event",
            packageName = "pkg",
            sourceId = sourceId,
            notificationKey = "key",
            notificationId = 1,
            notificationTag = null,
            postTimeEpochMillis = 0,
            capturedAtEpochMillis = 0,
            rawText = "raw",
            rawCandidatesJson = "{}",
            envelopeJson = "{}",
            envelopeSha256 = "sha",
            envelopeUtf8Bytes = 2,
        )
}
