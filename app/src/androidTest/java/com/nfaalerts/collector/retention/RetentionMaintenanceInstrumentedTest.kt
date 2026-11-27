package com.nfaalerts.collector.retention

import android.util.Log
import androidx.room3.Room
import androidx.room3.immediateTransaction
import androidx.room3.useReaderConnection
import androidx.room3.useWriterConnection
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nfaalerts.collector.config.RetentionConfig
import com.nfaalerts.collector.data.CapturedNotificationEntity
import com.nfaalerts.collector.data.DeliveryOutboxEntity
import com.nfaalerts.collector.data.DeliveryState
import com.nfaalerts.collector.data.NfaCollectorDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicLong

@RunWith(AndroidJUnit4::class)
class RetentionMaintenanceInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private var database: NfaCollectorDatabase? = null

    @After
    fun tearDown() {
        database?.close()
    }

    @Test
    fun oneHundredThousandRowsPurgeInBoundedBatchesWithoutBlockingCapture() =
        runBlocking {
            database = inMemoryDatabase()
            seed(total = 100_000, sent = 100_000)
            seedUnsent(count = 100)

            val dbBytes = AtomicLong(0)
            val maintenance = maintenance(dbBytes)
            val retention = RetentionConfig(sentDays = 180, maxSentRows = 10_000)
            val purge = async(Dispatchers.IO) { maintenance.runScheduled(retention) }

            val latencies = mutableListOf<Long>()
            repeat(20) { index ->
                val startedAt = System.nanoTime()
                database!!.captureWriteDao().insertCapture(
                    probeCapture("probe-$index"),
                    probeOutbox("probe-$index"),
                )
                latencies += (System.nanoTime() - startedAt) / NANOS_PER_MILLI
                delay(5)
            }
            val outcome = purge.await()

            assertEquals(90_000, outcome.deletedSentRows)
            assertEquals(900, outcome.batches)
            assertEquals(10_000, database!!.retentionDao().sentRowCount())
            assertEquals(120, database!!.captureReadDao().unsentCount())
            assertEquals(90_000, tombstoneCount())
            assertNotNull(database!!.retentionDao().tombstone("e-1"))
            assertNull(database!!.retentionDao().tombstone("e-100000"))
            assertNotNull(database!!.captureReadDao().capture("e-100000"))
            assertNull(database!!.captureReadDao().capture("e-1"))
            assertTrue(
                "Purge must not block capture for more than ${MAX_CAPTURE_BLOCK_MS}ms; max=${latencies.max()}",
                latencies.max() < MAX_CAPTURE_BLOCK_MS,
            )
            Log.i(
                "RetentionTest",
                "RETENTION_PURGE_100K deleted=${outcome.deletedSentRows} batches=${outcome.batches} " +
                    "durationMs=${outcome.durationMillis} maxCaptureLatencyMs=${latencies.max()}",
            )

            val secondPass = maintenance.runScheduled(retention)
            assertEquals(0, secondPass.deletedSentRows)
            assertEquals(90_000, tombstoneCount())
        }

    @Test
    fun unsentRowsSurviveAgeAndPressurePurges() =
        runBlocking {
            database = inMemoryDatabase()
            seed(total = 50, sent = 50, sentAtBase = 1L)
            val unsentStates =
                listOf(
                    DeliveryState.PENDING,
                    DeliveryState.SENDING,
                    DeliveryState.RETRY_WAIT,
                    DeliveryState.PAUSED_AUTH,
                    DeliveryState.BLOCKED_CONTRACT,
                    DeliveryState.QUARANTINED,
                )
            unsentStates.forEachIndexed { index, state ->
                seedUnsentRow("unsent-$index", state, capturedAt = 1L, updatedAt = 1L)
            }

            val maintenance = maintenance(AtomicLong(0))
            val outcome = maintenance.runScheduled(RetentionConfig(sentDays = 1, maxSentRows = 1))

            assertEquals(50, outcome.deletedSentRows)
            assertEquals(0, database!!.retentionDao().sentRowCount())
            assertEquals(unsentStates.size, database!!.captureReadDao().unsentCount())
            unsentStates.forEachIndexed { index, _ ->
                assertNotNull(database!!.captureReadDao().capture("unsent-$index"))
                assertNull(database!!.retentionDao().tombstone("unsent-$index"))
            }
        }

    @Test
    fun rowPressurePrunesOldestSentRowsAndKeepsUnsentRows() =
        runBlocking {
            database = inMemoryDatabase()
            seed(total = 1_200, sent = 1_200, sentAtBase = 10_000L)
            seedUnsent(count = 300, capturedAt = 1L, updatedAt = 1L)

            val retention =
                RetentionConfig(
                    sentDays = 180,
                    maxSentRows = 100_000,
                    pressureMaxRows = 1_000,
                    pressureMaxBytes = 200L * 1024L * 1024L,
                )
            val release = maintenance(AtomicLong(0)).releasePressureIfNeeded(retention)

            assertNotNull(release)
            assertTrue(release!!.rowsTriggered)
            assertFalse(release.bytesTriggered)
            assertEquals(750, release.deletedSentRows)
            assertEquals(450, database!!.retentionDao().sentRowCount())
            assertEquals(300, database!!.captureReadDao().unsentCount())
            assertEquals(750, release.after.capturedRows)
            assertNotNull(database!!.retentionDao().tombstone("e-1"))
            assertNull(database!!.retentionDao().tombstone("e-1500"))
        }

    @Test
    fun bytePressurePrunesABoundedSliceOfOldestSentRows() =
        runBlocking {
            database = inMemoryDatabase()
            seed(total = 6_000, sent = 6_000, sentAtBase = 10_000L)
            val dbBytes = AtomicLong(210L * 1024L * 1024L)

            val retention =
                RetentionConfig(
                    sentDays = 180,
                    maxSentRows = 100_000,
                    pressureMaxRows = 50_000,
                    pressureMaxBytes = 200L * 1024L * 1024L,
                )
            val release = maintenance(dbBytes).releasePressureIfNeeded(retention)

            assertNotNull(release)
            assertFalse(release!!.rowsTriggered)
            assertTrue(release.bytesTriggered)
            assertEquals(5_000, release.deletedSentRows)
            assertEquals(1_000, database!!.retentionDao().sentRowCount())
        }

    private fun maintenance(dbBytes: AtomicLong) =
        RetentionMaintenance(
            database = database!!,
            databaseSizeBytes = { dbBytes.get() },
            ioDispatcher = Dispatchers.IO,
            clock = { FIXED_NOW },
        )

    private suspend fun seed(
        total: Int,
        sent: Int,
        sentAtBase: Long = FIXED_NOW - total,
    ) {
        database!!.useWriterConnection { transactor ->
            transactor.immediateTransaction {
                usePrepared(CAPTURE_INSERT) { statement ->
                    for (index in 1..total) {
                        statement.bindText(1, "e-$index")
                        statement.bindText(2, "com.example.bnn")
                        statement.bindText(3, "bnn")
                        statement.bindText(4, "key-$index")
                        statement.bindInt(5, index)
                        statement.bindNull(6)
                        statement.bindLong(7, index.toLong())
                        statement.bindLong(8, index.toLong())
                        statement.bindNull(9)
                        statement.bindText(10, "{}")
                        statement.bindText(11, "{}")
                        statement.bindText(12, "sha-$index")
                        statement.bindInt(13, 2)
                        statement.bindText(14, "LIVE")
                        statement.step()
                        statement.reset()
                        statement.clearBindings()
                    }
                }
                usePrepared(OUTBOX_INSERT) { statement ->
                    for (index in 1..total) {
                        statement.bindText(1, "e-$index")
                        statement.bindText(2, if (index <= sent) "SENT" else "PENDING")
                        statement.bindInt(3, 0)
                        statement.bindNull(4)
                        statement.bindNull(5)
                        statement.bindNull(6)
                        statement.bindNull(7)
                        statement.bindNull(8)
                        if (index <= sent) {
                            statement.bindLong(9, sentAtBase + index)
                        } else {
                            statement.bindNull(9)
                        }
                        statement.bindNull(10)
                        statement.bindNull(11)
                        statement.bindText(12, "ingest-$index")
                        statement.bindNull(13)
                        statement.bindText(14, "1.2.0")
                        statement.bindLong(15, sentAtBase + index)
                        statement.bindLong(16, sentAtBase + index)
                        statement.step()
                        statement.reset()
                        statement.clearBindings()
                    }
                }
            }
        }
    }

    private suspend fun seedUnsent(
        count: Int,
        capturedAt: Long = 1L,
        updatedAt: Long = 1L,
    ) {
        repeat(count) { index ->
            seedUnsentRow("pending-$index", DeliveryState.PENDING, capturedAt, updatedAt)
        }
    }

    private suspend fun seedUnsentRow(
        eventId: String,
        state: DeliveryState,
        capturedAt: Long,
        updatedAt: Long,
    ) {
        database!!.captureWriteDao().insertCapture(
            probeCapture(eventId, capturedAt),
            probeOutbox(eventId, state, updatedAt),
        )
    }

    private suspend fun tombstoneCount(): Int =
        database!!.useReaderConnection { transactor ->
            transactor.usePrepared("SELECT COUNT(*) FROM capture_retention_tombstones") { statement ->
                statement.step()
                statement.getInt(0)
            }
        }

    private fun probeCapture(
        eventId: String,
        capturedAt: Long = FIXED_NOW,
    ) = CapturedNotificationEntity(
        eventId = eventId,
        packageName = "com.example.bnn",
        sourceId = "bnn",
        notificationKey = "key-$eventId",
        notificationId = 1,
        notificationTag = null,
        postTimeEpochMillis = capturedAt,
        capturedAtEpochMillis = capturedAt,
        rawText = "raw",
        rawCandidatesJson = "{}",
        envelopeJson = "{}",
        envelopeSha256 = "sha-$eventId",
        envelopeUtf8Bytes = 2,
    )

    private fun probeOutbox(
        eventId: String,
        state: DeliveryState = DeliveryState.PENDING,
        updatedAt: Long = FIXED_NOW,
    ) = DeliveryOutboxEntity(
        eventId = eventId,
        state = state,
        createdAtEpochMillis = updatedAt,
        updatedAtEpochMillis = updatedAt,
        sentAtEpochMillis = if (state == DeliveryState.SENT) updatedAt else null,
    )

    private fun inMemoryDatabase(): NfaCollectorDatabase =
        Room
            .inMemoryDatabaseBuilder(context, NfaCollectorDatabase::class.java)
            .build()

    private companion object {
        const val FIXED_NOW = 1_700_000_000_000L
        const val NANOS_PER_MILLI = 1_000_000L
        const val MAX_CAPTURE_BLOCK_MS = 250L
        const val CAPTURE_INSERT =
            "INSERT INTO captured_notifications (" +
                "eventId,packageName,sourceId,notificationKey,notificationId,notificationTag," +
                "postTimeEpochMillis,capturedAtEpochMillis,rawText,rawCandidatesJson,envelopeJson," +
                "envelopeSha256,envelopeUtf8Bytes,capturePath) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
        const val OUTBOX_INSERT =
            "INSERT INTO delivery_outbox (" +
                "eventId,state,attemptCount,nextAttemptAtEpochMillis,leaseOwner,leaseExpiresAtEpochMillis," +
                "lastAttemptAtEpochMillis,pausedAtConfigRevision,sentAtEpochMillis,lastHttpStatus,lastErrorCode," +
                "serverIngestId,serverReceivedAt,contractVersion,createdAtEpochMillis,updatedAtEpochMillis) " +
                "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
    }
}
