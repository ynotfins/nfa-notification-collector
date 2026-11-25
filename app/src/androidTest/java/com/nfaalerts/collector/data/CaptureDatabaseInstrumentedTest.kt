package com.nfaalerts.collector.data

import androidx.room3.Room
import androidx.room3.executeSQL
import androidx.room3.useWriterConnection
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
    fun triggerFailureAfterCaptureInsertRollsBackTheWholeTransaction() =
        runBlocking {
            val writer = database.captureWriteDao()
            database.useWriterConnection { connection ->
                connection.executeSQL(
                    """
                    CREATE TRIGGER abort_outbox_insert
                    BEFORE INSERT ON delivery_outbox
                    BEGIN
                      SELECT RAISE(ABORT, 'forced outbox failure');
                    END
                    """.trimIndent(),
                )
            }

            runCatching {
                writer.insertCapture(
                    capture("must-roll-back", "bnn"),
                    outbox("must-roll-back", DeliveryState.PENDING),
                )
            }

            assertNull(database.captureReadDao().capture("must-roll-back"))
            assertEquals(0, database.captureReadDao().captureCount())
        }

    @Test
    fun fileBackedCaptureAndOutboxSurviveCloseAndReopen() =
        runBlocking {
            database.close()
            val context = ApplicationProvider.getApplicationContext<android.content.Context>()
            val name = "capture-reopen-${System.nanoTime()}.db"
            try {
                database = Room.databaseBuilder(context, NfaCollectorDatabase::class.java, name).build()
                database.captureWriteDao().insertCapture(
                    capture("durable", "bnn"),
                    outbox("durable", DeliveryState.PENDING),
                )
                database.close()

                database = Room.databaseBuilder(context, NfaCollectorDatabase::class.java, name).build()
                assertEquals("durable", database.captureReadDao().capture("durable")?.eventId)
                assertEquals(DeliveryState.PENDING, database.captureReadDao().outbox("durable")?.state)
            } finally {
                database.close()
                context.deleteDatabase(name)
                database =
                    Room
                        .inMemoryDatabaseBuilder(
                            context,
                            NfaCollectorDatabase::class.java,
                        ).build()
            }
        }

    @Test
    fun repeatedAndroidIdentityPersistsAsDistinctEvents() =
        runBlocking {
            val writer = database.captureWriteDao()
            writer.insertCapture(capture("event-a", "bnn"), outbox("event-a", DeliveryState.PENDING))
            writer.insertCapture(capture("event-b", "bnn"), outbox("event-b", DeliveryState.PENDING))

            assertEquals(2, database.captureReadDao().captureCount())
        }

    @Test
    fun protectedShadeDiagnosticsSurviveTheNonSelectedFlood() =
        runBlocking {
            val dao = database.diagnosticsDao()
            val base = 1_000_000L
            repeat(10) { index ->
                dao.insertFromRepository(
                    DiagnosticEventEntity("flood-$index", base + index, "CAPTURE_DROPPED", "{}"),
                )
            }
            repeat(5) { index ->
                dao.insertFromRepository(
                    DiagnosticEventEntity("shade-$index", base + 100 + index, "SHADE_CAP_GUARD", "{}"),
                )
            }

            dao.prune(
                cutoffEpochMillis = 0,
                maxRows = 3,
                protectedEventCodes = listOf("SHADE_CAP_GUARD"),
                protectedMaxRows = 5,
            )

            val rows = dao.recent(100)
            assertEquals(3, rows.count { it.eventCode == "CAPTURE_DROPPED" })
            assertEquals(5, rows.count { it.eventCode == "SHADE_CAP_GUARD" })
        }

    @Test
    fun protectedShadeDiagnosticsOutliveTheAgeCutoff() =
        runBlocking {
            val dao = database.diagnosticsDao()
            val base = 1_000_000L
            dao.insertFromRepository(DiagnosticEventEntity("old-flood", base, "CAPTURE_DROPPED", "{}"))
            dao.insertFromRepository(DiagnosticEventEntity("old-shade", base + 1, "SHADE_CAP_GUARD", "{}"))

            dao.prune(
                cutoffEpochMillis = base + 10,
                maxRows = 100,
                protectedEventCodes = listOf("SHADE_CAP_GUARD"),
                protectedMaxRows = 100,
            )

            assertEquals(listOf("old-shade"), dao.recent(100).map { it.diagnosticId })
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
