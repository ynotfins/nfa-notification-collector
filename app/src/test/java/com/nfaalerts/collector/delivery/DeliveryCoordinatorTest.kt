package com.nfaalerts.collector.delivery

import com.nfaalerts.collector.config.EndpointProfile
import com.nfaalerts.collector.data.CapturedNotificationEntity
import com.nfaalerts.collector.data.DeliveryOutboxEntity
import com.nfaalerts.collector.data.DeliveryState
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
            assertEquals("RETRY_WAIT:21601000:NETWORK", store.transition)
            assertEquals(21_601_000L, store.scheduledAt)
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

        override suspend fun capture(eventId: String) = capture

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
            transition = "RETRY_WAIT:$nextAttemptAtEpochMillis:${result.code}"
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
