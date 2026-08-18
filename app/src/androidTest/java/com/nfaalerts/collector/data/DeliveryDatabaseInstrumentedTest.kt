package com.nfaalerts.collector.data

import androidx.room3.Room
import androidx.room3.testing.MigrationTestHelper
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeliveryDatabaseInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private var database: NfaCollectorDatabase? = null

    @get:Rule
    val migrationHelper =
        MigrationTestHelper(
            instrumentation = InstrumentationRegistry.getInstrumentation(),
            file = context.getDatabasePath(MIGRATION_DB),
            driver = AndroidSQLiteDriver(),
            databaseClass = NfaCollectorDatabase::class,
        )

    @After
    fun tearDown() {
        database?.close()
        context.deleteDatabase(MIGRATION_DB)
    }

    @Test
    fun migrationOneToTwoPreservesCaptureAndAddsDeliveryRevisionFields() =
        runBlocking {
            migrationHelper.createDatabase(1).use { connection ->
                connection.execSQL(
                    """
                    INSERT INTO captured_notifications
                    VALUES ('migrate','pkg','bnn','key',1,NULL,10,20,'raw','{}','{}','sha',2)
                    """.trimIndent(),
                )
                connection.execSQL(
                    """
                    INSERT INTO delivery_outbox
                    VALUES ('migrate','PENDING',0,NULL,NULL,NULL,NULL,NULL,NULL,NULL,20,20)
                    """.trimIndent(),
                )
            }

            migrationHelper.runMigrationsAndValidate(2, listOf(NfaCollectorDatabase.MIGRATION_1_2)).use { connection ->
                connection
                    .prepare(
                        """
                        SELECT lastAttemptAtEpochMillis, pausedAtConfigRevision, sentAtEpochMillis
                        FROM delivery_outbox WHERE eventId='migrate'
                        """.trimIndent(),
                    ).use { statement ->
                        assertTrue(statement.step())
                        assertTrue(statement.isNull(0))
                        assertTrue(statement.isNull(1))
                        assertTrue(statement.isNull(2))
                    }
            }

            database = NfaCollectorDatabase.create(context, MIGRATION_DB)
            assertEquals("raw", database!!.captureReadDao().capture("migrate")?.rawText)
            assertEquals(DeliveryState.PENDING, database!!.captureReadDao().outbox("migrate")?.state)
        }

    @Test
    fun claimDueIsAtomicAndDatabaseTimeIsAuthoritative() =
        runBlocking {
            database = inMemoryDatabase()
            insert("due", DeliveryState.PENDING, nextAttemptAt = null)
            insert("future", DeliveryState.RETRY_WAIT, nextAttemptAt = 10_001L)

            val claims =
                coroutineScope {
                    listOf(
                        async { database!!.deliveryDao().claimDue("immediate", 10_000L, 610_000L) },
                        async { database!!.deliveryDao().claimDue("worker", 10_000L, 610_000L) },
                    ).awaitAll()
                }

            assertEquals(1, claims.count { it?.eventId == "due" })
            assertTrue(claims.filterNotNull().single().attemptCount == 1)
            assertNull(database!!.deliveryDao().claimDue("worker", 10_000L, 610_000L))
            assertEquals(DeliveryState.RETRY_WAIT, database!!.captureReadDao().outbox("future")?.state)
        }

    @Test
    fun onlyLeaseOwnerCanPerformLegalTerminalOrRetryTransitions() =
        runBlocking {
            database = inMemoryDatabase()
            insert("event", DeliveryState.PENDING)
            assertNotNull(database!!.deliveryDao().claimDue("owner", 100L, 600_100L))

            assertEquals(
                0,
                database!!.deliveryDao().markRetryWait(
                    "event",
                    "wrong-owner",
                    200L,
                    500L,
                    503,
                    "HTTP_503",
                ),
            )
            assertEquals(
                1,
                database!!.deliveryDao().markSent(
                    "event",
                    "owner",
                    200L,
                    "123e4567-e89b-12d3-a456-426614174000",
                    "2026-08-18T12:00:00Z",
                ),
            )
            assertEquals(
                0,
                database!!.deliveryDao().markRetryWait("event", "owner", 300L, 600L, null, "NETWORK"),
            )
            assertEquals(DeliveryState.SENT, database!!.captureReadDao().outbox("event")?.state)
        }

    @Test
    fun staleSendingAndPausedAuthRecoverOnlyAtTheirGuards() =
        runBlocking {
            database = inMemoryDatabase()
            insert("stale", DeliveryState.PENDING)
            insert("paused", DeliveryState.PENDING)
            database!!.deliveryDao().claimDue("owner", 0L, 600_000L)
            database!!.deliveryDao().claimDue("owner", 0L, 600_000L)
            database!!.deliveryDao().markPausedAuth("paused", "owner", 10L, 7L, 401, "HTTP_401")

            assertEquals(0, database!!.deliveryDao().recoverStaleSending(599_999L))
            assertEquals(1, database!!.deliveryDao().recoverStaleSending(600_001L))
            assertEquals(0, database!!.deliveryDao().requeuePausedAuth(7L, 20L))
            assertEquals(1, database!!.deliveryDao().requeuePausedAuth(8L, 21L))
            assertEquals(DeliveryState.PENDING, database!!.captureReadDao().outbox("paused")?.state)
        }

    @Test
    fun retentionTombstonesSentBeforeDeletingPayloadAndNeverDeletesNonSent() =
        runBlocking {
            database = inMemoryDatabase()
            insert("old-sent", DeliveryState.SENT, updatedAt = 1L, sentAt = 1L)
            insert("new-sent", DeliveryState.SENT, updatedAt = 900L, sentAt = 900L)
            insert("pending", DeliveryState.PENDING, updatedAt = 1L)

            val deleted =
                database!!.retentionDao().pruneSent(
                    cutoffEpochMillis = 100L,
                    maxSentRows = 10,
                    nowEpochMillis = 1_000L,
                )

            assertEquals(1, deleted)
            assertNull(database!!.captureReadDao().capture("old-sent"))
            assertNotNull(database!!.retentionDao().tombstone("old-sent"))
            assertNotNull(database!!.captureReadDao().capture("new-sent"))
            assertNotNull(database!!.captureReadDao().capture("pending"))
        }

    @Test
    fun diagnosticsAreBoundedByAgeAndCount() =
        runBlocking {
            database = inMemoryDatabase()
            val dao = database!!.diagnosticsDao()
            (1L..5L).forEach { index ->
                dao.insert(
                    DiagnosticEventEntity(
                        diagnosticId = "d-$index",
                        createdAtEpochMillis = index,
                        eventCode = "SAFE_EVENT",
                        safeDetailsJson = "{}",
                    ),
                )
            }

            dao.prune(cutoffEpochMillis = 2L, maxRows = 2)

            assertEquals(listOf("d-5", "d-4"), dao.recent(10).map(DiagnosticEventEntity::diagnosticId))
        }

    private fun inMemoryDatabase() = Room.inMemoryDatabaseBuilder(context, NfaCollectorDatabase::class.java).build()

    private suspend fun insert(
        eventId: String,
        state: DeliveryState,
        nextAttemptAt: Long? = null,
        updatedAt: Long = 0L,
        sentAt: Long? = null,
    ) {
        database!!.captureWriteDao().insertCapture(
            capture(eventId),
            DeliveryOutboxEntity(
                eventId = eventId,
                state = state,
                nextAttemptAtEpochMillis = nextAttemptAt,
                sentAtEpochMillis = sentAt,
                createdAtEpochMillis = 0L,
                updatedAtEpochMillis = updatedAt,
            ),
        )
    }

    private fun capture(eventId: String) =
        CapturedNotificationEntity(
            eventId = eventId,
            packageName = "com.example.bnn",
            sourceId = "bnn",
            notificationKey = "key-$eventId",
            notificationId = 1,
            notificationTag = null,
            postTimeEpochMillis = 0L,
            capturedAtEpochMillis = 0L,
            rawText = "raw-$eventId",
            rawCandidatesJson = "{}",
            envelopeJson = "{}",
            envelopeSha256 = "sha-$eventId",
            envelopeUtf8Bytes = 2,
        )

    private companion object {
        const val MIGRATION_DB = "delivery-migration.db"
    }
}
