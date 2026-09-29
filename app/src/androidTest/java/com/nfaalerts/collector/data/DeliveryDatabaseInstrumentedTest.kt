package com.nfaalerts.collector.data

import androidx.room3.Room
import androidx.room3.testing.MigrationTestHelper
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nfaalerts.collector.config.EndpointProfile
import com.nfaalerts.collector.delivery.BearerLoad
import com.nfaalerts.collector.delivery.DeliveryCoordinator
import com.nfaalerts.collector.delivery.DeliveryScheduler
import com.nfaalerts.collector.delivery.IngestResult
import com.nfaalerts.collector.delivery.RoomDeliveryStore
import com.nfaalerts.collector.delivery.RuntimeDeliverySettings
import com.nfaalerts.collector.diagnostics.DiagnosticRepository
import com.nfaalerts.collector.diagnostics.RoomDiagnosticStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
                    val start = CompletableDeferred<Unit>()
                    listOf(
                        async(Dispatchers.IO) {
                            start.await()
                            database!!.deliveryDao().claimDue("immediate", 10_000L, 610_000L)
                        },
                        async(Dispatchers.IO) {
                            start.await()
                            database!!.deliveryDao().claimDue("worker", 10_000L, 610_000L)
                        },
                    ).also { start.complete(Unit) }.awaitAll()
                }

            assertEquals(1, claims.count { it?.eventId == "due" })
            assertTrue(claims.filterNotNull().single().attemptCount == 1)
            assertNull(database!!.deliveryDao().claimDue("worker", 10_000L, 610_000L))
            assertEquals(DeliveryState.RETRY_WAIT, database!!.captureReadDao().outbox("future")?.state)
        }

    @Test
    fun orderedReplayBlocksNewerDueRowsUntilTheOldestCaptureIsDue() =
        runBlocking {
            database = inMemoryDatabase()
            insert("z-oldest", DeliveryState.RETRY_WAIT, nextAttemptAt = 2_000L, capturedAt = 1L)
            insert("a-newer", DeliveryState.PENDING, nextAttemptAt = null, capturedAt = 2L)

            assertNull(database!!.deliveryDao().claimDue("worker", 1_000L, 601_000L))
            assertEquals(
                "z-oldest",
                database!!.deliveryDao().claimDue("worker", 2_000L, 602_000L)?.eventId,
            )
        }

    @Test
    fun manualExpediteMakesTheOldestRetryImmediatelyClaimableWithoutReordering() =
        runBlocking {
            database = inMemoryDatabase()
            insert("z-oldest", DeliveryState.RETRY_WAIT, nextAttemptAt = 20_000L, capturedAt = 1L)
            insert("a-newer", DeliveryState.PENDING, nextAttemptAt = null, capturedAt = 2L)

            assertEquals(1, database!!.deliveryDao().expediteRetryWait(1_000L))
            assertEquals(
                "z-oldest",
                database!!.deliveryDao().claimDue("manual", 1_000L, 601_000L)?.eventId,
            )
        }

    @Test
    fun offlineQueueReplaysEveryCaptureInOrderWhenConnectivityReturns() =
        runBlocking {
            database = inMemoryDatabase()
            insert("z-first", DeliveryState.PENDING, capturedAt = 1L)
            insert("y-second", DeliveryState.PENDING, capturedAt = 2L)
            insert("x-third", DeliveryState.PENDING, capturedAt = 3L)
            var online = false
            var now = 1_000L
            val delivered = mutableListOf<String>()
            val coordinator =
                DeliveryCoordinator(
                    store = RoomDeliveryStore(database!!),
                    settings = {
                        RuntimeDeliverySettings(
                            endpoint = EndpointProfile("https://example.invalid", "/v1/ingest/alerts"),
                            relevantRevision = 1L,
                            bearer = BearerLoad.Present(CharArray(43) { 'x' }),
                        )
                    },
                    transport = { _, _, payload ->
                        if (!online) {
                            IngestResult.RetryWait("NETWORK", null)
                        } else {
                            val metadata =
                                Json
                                    .parseToJsonElement(payload.bodyBytes.decodeToString())
                                    .jsonObject
                                    .getValue("metadata")
                                    .jsonObject
                            delivered += metadata.getValue("clientEventId").jsonPrimitive.content
                            IngestResult.Sent(
                                "123e4567-e89b-12d3-a456-426614174000",
                                "2026-08-18T12:00:00Z",
                            )
                        }
                    },
                    scheduler =
                        object : DeliveryScheduler {
                            override fun ensureScheduled(dueAtEpochMillis: Long) = Unit
                        },
                    clock = { now },
                )

            assertEquals(1, coordinator.drainAvailable("offline"))
            assertEquals(DeliveryState.RETRY_WAIT, database!!.captureReadDao().outbox("z-first")?.state)
            assertEquals(DeliveryState.PENDING, database!!.captureReadDao().outbox("y-second")?.state)
            assertEquals(DeliveryState.PENDING, database!!.captureReadDao().outbox("x-third")?.state)

            online = true
            now = 49L * 60L * 60L * 1_000L
            assertEquals(3, coordinator.drainAvailable("online-after-49h"))
            assertEquals(listOf("z-first", "y-second", "x-third"), delivered)
            assertEquals(DeliveryState.SENT, database!!.captureReadDao().outbox("z-first")?.state)
            assertEquals(DeliveryState.SENT, database!!.captureReadDao().outbox("y-second")?.state)
            assertEquals(DeliveryState.SENT, database!!.captureReadDao().outbox("x-third")?.state)
        }

    @Test
    fun sendingLeaseIsTheNextWakeBeforeExpiryAndBecomesDueAfterRecovery() =
        runBlocking {
            database = inMemoryDatabase()
            insert("leased", DeliveryState.PENDING)
            assertNotNull(database!!.deliveryDao().claimDue("owner", 0L, 600_000L))

            assertEquals(600_000L, database!!.deliveryDao().nextDueAtEpochMillis())
            assertEquals(0, database!!.deliveryDao().recoverStaleSending(599_999L))
            assertEquals(DeliveryState.SENDING, database!!.captureReadDao().outbox("leased")?.state)
            assertEquals(600_000L, database!!.deliveryDao().nextDueAtEpochMillis())

            assertEquals(1, database!!.deliveryDao().recoverStaleSending(600_001L))
            assertEquals(600_001L, database!!.deliveryDao().nextDueAtEpochMillis())
            assertNotNull(database!!.deliveryDao().claimDue("recovery", 600_001L, 1_200_001L))
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
            database!!.deliveryDao().claimDue("owner", 0L, 600_000L)

            assertEquals(0, database!!.deliveryDao().recoverStaleSending(599_999L))
            assertEquals(1, database!!.deliveryDao().recoverStaleSending(600_001L))
            assertNotNull(database!!.deliveryDao().claimDue("recovery", 600_001L, 1_200_001L))
            database!!.deliveryDao().markSent(
                "stale",
                "recovery",
                600_002L,
                "123e4567-e89b-12d3-a456-426614174000",
                "2026-08-18T12:00:00Z",
            )

            insert("paused", DeliveryState.PENDING, capturedAt = 1L)
            assertNotNull(database!!.deliveryDao().claimDue("owner", 700_000L, 1_300_000L))
            database!!.deliveryDao().markPausedAuth("paused", "owner", 700_001L, 7L, 401, "HTTP_401")
            assertEquals(0, database!!.deliveryDao().requeuePausedAuth(7L, 700_002L))
            assertEquals(1, database!!.deliveryDao().requeuePausedAuth(8L, 700_003L))
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
                DiagnosticRepository(
                    RoomDiagnosticStore(dao),
                    clock = { index },
                    idFactory = { "d-$index" },
                    retentionDays = 14,
                    maxRows = 2,
                ).record("DELIVERY_RETRY", mapOf("state" to "RETRY_WAIT"))
            }

            assertEquals(listOf("d-5", "d-4"), dao.recent(10).map(DiagnosticEventEntity::diagnosticId))
        }

    @Test
    fun collectorStatusFlowUsesAllRowsAndLatestSafeFacts() =
        runBlocking {
            database = inMemoryDatabase()
            (1L..101L).forEach { index ->
                insert("pending-$index", DeliveryState.PENDING, capturedAt = index)
            }
            insert(
                eventId = "sent",
                state = DeliveryState.SENT,
                updatedAt = 200,
                sentAt = 190,
                capturedAt = 200,
                serverReceivedAt = "2026-08-18T12:00:00Z",
            )
            insert(
                eventId = "retry",
                state = DeliveryState.RETRY_WAIT,
                updatedAt = 300,
                capturedAt = 300,
                lastErrorCode = "HTTP_503",
            )

            val status = database!!.captureReadDao().collectorStatus().first()

            assertEquals(103L, status.totalCount)
            assertEquals(102L, status.nonSentCount)
            assertEquals(101L, status.pendingCount)
            assertEquals(1L, status.retryWaitCount)
            assertEquals(1L, status.sentCount)
            assertEquals(300L, status.lastCaptureAtEpochMillis)
            assertEquals(190L, status.lastSentAtEpochMillis)
            assertEquals("2026-08-18T12:00:00Z", status.lastServerReceivedAt)
            assertEquals("HTTP_503", status.latestSafeError)
        }

    private fun inMemoryDatabase() = Room.inMemoryDatabaseBuilder(context, NfaCollectorDatabase::class.java).build()

    private suspend fun insert(
        eventId: String,
        state: DeliveryState,
        nextAttemptAt: Long? = null,
        updatedAt: Long = 0L,
        sentAt: Long? = null,
        capturedAt: Long = 0L,
        serverReceivedAt: String? = null,
        lastErrorCode: String? = null,
    ) {
        database!!.captureWriteDao().insertCapture(
            capture(eventId, capturedAt),
            DeliveryOutboxEntity(
                eventId = eventId,
                state = state,
                nextAttemptAtEpochMillis = nextAttemptAt,
                sentAtEpochMillis = sentAt,
                lastErrorCode = lastErrorCode,
                serverReceivedAt = serverReceivedAt,
                createdAtEpochMillis = 0L,
                updatedAtEpochMillis = updatedAt,
            ),
        )
    }

    private fun capture(
        eventId: String,
        capturedAt: Long = 0L,
    ) = CapturedNotificationEntity(
        eventId = eventId,
        packageName = "com.example.bnn",
        sourceId = "bnn",
        notificationKey = "key-$eventId",
        notificationId = 1,
        notificationTag = null,
        postTimeEpochMillis = 0L,
        capturedAtEpochMillis = capturedAt,
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
