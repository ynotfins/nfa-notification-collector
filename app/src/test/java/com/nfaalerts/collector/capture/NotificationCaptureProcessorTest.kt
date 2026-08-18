package com.nfaalerts.collector.capture

import com.nfaalerts.collector.config.SourceSelection
import com.nfaalerts.collector.data.CapturedNotificationEntity
import com.nfaalerts.collector.data.DeliveryOutboxEntity
import com.nfaalerts.collector.data.DeliveryState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationCaptureProcessorTest {
    @Test
    fun `limit quarantine never persists unbounded raw content or candidate JSON`() =
        runBlocking {
            val huge = "private".repeat(100)
            val writes = mutableListOf<Pair<CapturedNotificationEntity, DeliveryOutboxEntity>>()
            val processor =
                processor(
                    reader = { _, _ ->
                        AndroidNotificationSnapshot(
                            envelopeValues = mapOf("value" to huge),
                            rawTextSelection =
                                RawTextSelection(
                                    rawText = huge,
                                    selectedField = RawTextField.TEXT,
                                    candidates = mapOf(RawTextField.TEXT to listOf(huge)),
                                ),
                        )
                    },
                    serializer = SafeCanonicalSerializer(SafeSerializerLimits(maxStringUtf8Bytes = 16)),
                    writes = writes,
                )

            processor.process(request())

            val (capture, outbox) = writes.single()
            assertEquals(DeliveryState.QUARANTINED, outbox.state)
            assertEquals("LOCAL_ENVELOPE_LIMIT", outbox.lastErrorCode)
            assertNull(capture.rawText)
            assertEquals("{}", capture.rawCandidatesJson)
            assertFalse(capture.envelopeJson.contains(huge))
            assertTrue(capture.envelopeUtf8Bytes < 64 * 1024)
        }

    @Test
    fun `reader failure attempts a minimal immutable quarantined capture`() =
        runBlocking {
            val writes = mutableListOf<Pair<CapturedNotificationEntity, DeliveryOutboxEntity>>()
            val processor =
                processor(
                    reader = { _, _ -> error("private failure message") },
                    writes = writes,
                )

            processor.process(request())

            val (capture, outbox) = writes.single()
            assertEquals(DeliveryState.QUARANTINED, outbox.state)
            assertEquals("CAPTURE_PROCESSING_FAILURE", outbox.lastErrorCode)
            assertNull(capture.rawText)
            assertEquals("{}", capture.rawCandidatesJson)
            assertTrue(capture.envelopeJson.contains(IllegalStateException::class.java.name))
            assertFalse(capture.envelopeJson.contains("private failure message"))
        }

    @Test
    fun `failed normal persistence makes one bounded fallback persistence attempt`() =
        runBlocking {
            val writes = mutableListOf<Pair<CapturedNotificationEntity, DeliveryOutboxEntity>>()
            var calls = 0
            val processor =
                NotificationCaptureProcessor(
                    reader = NotificationContentReader { _, _ -> emptySnapshot() },
                    applicationMetadataResolver = ApplicationMetadataResolver { _, _ -> ApplicationMetadata(null, 42) },
                    persistence =
                        CapturePersistence { capture, outbox ->
                            calls += 1
                            if (calls == 1) error("first write failed")
                            writes += capture to outbox
                        },
                )

            processor.process(request())

            assertEquals(2, calls)
            assertEquals("CAPTURE_PROCESSING_FAILURE", writes.single().second.lastErrorCode)
        }

    @Test
    fun `successful envelope retains status bar group identity`() =
        runBlocking {
            val writes = mutableListOf<Pair<CapturedNotificationEntity, DeliveryOutboxEntity>>()
            processor(reader = { _, _ -> emptySnapshot() }, writes = writes).process(request())

            val envelope = writes.single().first.envelopeJson
            assertTrue(envelope.contains("groupKey"))
            assertTrue(envelope.contains("group"))
            assertTrue(envelope.contains("overrideGroupKey"))
        }

    private fun processor(
        reader: (Any, List<RawTextField>) -> AndroidNotificationSnapshot,
        serializer: SafeCanonicalSerializer = SafeCanonicalSerializer(),
        writes: MutableList<Pair<CapturedNotificationEntity, DeliveryOutboxEntity>>,
    ) = NotificationCaptureProcessor(
        reader = NotificationContentReader(reader),
        applicationMetadataResolver = ApplicationMetadataResolver { _, _ -> ApplicationMetadata("Example", 42) },
        persistence = CapturePersistence { capture, outbox -> writes += capture to outbox },
        serializer = serializer,
    )

    private fun request() =
        DispatchedNotification(
            eventId = "event-1",
            capturedAtEpochMillis = 200L,
            identity =
                NotificationIdentity(
                    packageName = "com.example",
                    key = "key",
                    notificationId = 1,
                    tag = null,
                    postTimeEpochMillis = 100L,
                    uid = 42,
                    userId = 0,
                    isOngoing = false,
                    isClearable = true,
                    groupKey = "group",
                    overrideGroupKey = null,
                ),
            source =
                SourceSelection(
                    packageName = "com.example",
                    appLabel = "Example",
                    sourceId = "bnn",
                    enabled = true,
                    bnnMappingConfirmed = true,
                    rawTextOrder = RawTextField.DEFAULT_ORDER,
                ),
            notificationHandle = Any(),
        )

    private fun emptySnapshot() =
        AndroidNotificationSnapshot(
            envelopeValues = emptyMap(),
            rawTextSelection = RawTextSelection(null, null, emptyMap()),
        )
}
