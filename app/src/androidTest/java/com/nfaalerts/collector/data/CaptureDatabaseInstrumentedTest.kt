package com.nfaalerts.collector.data

import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CaptureDatabaseInstrumentedTest {
    private lateinit var database: NfaCollectorDatabase

    @Before
    fun setUp() {
        database =
            Room
                .inMemoryDatabaseBuilder(
                    ApplicationProvider.getApplicationContext(),
                    NfaCollectorDatabase::class.java,
                ).build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun bnnAndNonBnnCapturesReceiveTheirRequiredInitialStates() =
        runBlocking {
            val writer = database.captureWriteDao()
            writer.insertCapture(capture("bnn-event", "bnn"), outbox("bnn-event", DeliveryState.PENDING))
            writer.insertCapture(
                capture("other-event", "weather"),
                outbox("other-event", DeliveryState.BLOCKED_CONTRACT),
            )

            assertEquals(DeliveryState.PENDING, database.captureReadDao().outbox("bnn-event")?.state)
            assertEquals(
                DeliveryState.BLOCKED_CONTRACT,
                database.captureReadDao().outbox("other-event")?.state,
            )
        }

    @Test
    fun captureAndInitialOutboxInsertRollBackTogether() =
        runBlocking {
            val writer = database.captureWriteDao()
            writer.insertCapture(capture("existing", "bnn"), outbox("existing", DeliveryState.PENDING))

            runCatching {
                writer.insertCapture(capture("must-roll-back", "bnn"), outbox("existing", DeliveryState.PENDING))
            }

            assertNull(database.captureReadDao().capture("must-roll-back"))
            assertEquals(1, database.captureReadDao().captureCount())
        }

    @Test
    fun repeatedAndroidIdentityPersistsAsDistinctEvents() =
        runBlocking {
            val writer = database.captureWriteDao()
            writer.insertCapture(capture("event-a", "bnn"), outbox("event-a", DeliveryState.PENDING))
            writer.insertCapture(capture("event-b", "bnn"), outbox("event-b", DeliveryState.PENDING))

            assertEquals(2, database.captureReadDao().captureCount())
        }

    private fun capture(
        eventId: String,
        sourceId: String,
    ) = CapturedNotificationEntity(
        eventId = eventId,
        packageName = "com.example.same",
        sourceId = sourceId,
        notificationKey = "same-key",
        notificationId = 10,
        notificationTag = "same-tag",
        postTimeEpochMillis = 100L,
        capturedAtEpochMillis = 200L,
        rawText = "same text",
        rawCandidatesJson = "{}",
        envelopeJson = "{}",
        envelopeSha256 = "hash-$eventId",
        envelopeUtf8Bytes = 2,
    )

    private fun outbox(
        eventId: String,
        state: DeliveryState,
    ) = DeliveryOutboxEntity(
        eventId = eventId,
        state = state,
        createdAtEpochMillis = 200L,
        updatedAtEpochMillis = 200L,
    )
}
