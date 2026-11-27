package com.nfaalerts.collector.retention

import android.util.Log
import androidx.room3.Room
import androidx.room3.useReaderConnection
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nfaalerts.collector.config.RetentionConfig
import com.nfaalerts.collector.data.CapturedNotificationEntity
import com.nfaalerts.collector.data.DeliveryOutboxEntity
import com.nfaalerts.collector.data.DeliveryState
import com.nfaalerts.collector.data.NfaCollectorDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compressed 72-hour soak simulation: 100 posts/hour for 72 hours (7,200 captures) with an
 * hourly retention pass. This proves the policy under simulated long-run load; it is not a
 * wall-clock 72-hour device soak.
 */
@RunWith(AndroidJUnit4::class)
class RetentionSoakInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private var database: NfaCollectorDatabase? = null

    @After
    fun tearDown() {
        database?.close()
    }

    @Test
    fun compressedSeventyTwoHourSoakStaysInsidePolicyWithoutStalls() =
        runBlocking<Unit> {
            database = inMemoryDatabase()
            var now = START_EPOCH_MILLIS
            val maintenance =
                RetentionMaintenance(
                    database = database!!,
                    databaseSizeBytes = { 0 },
                    ioDispatcher = Dispatchers.IO,
                    clock = { now },
                )
            val retention = RetentionConfig(sentDays = 180, maxSentRows = 1_000)
            val latencies = mutableListOf<Long>()

            repeat(POSTS) { index ->
                val startedAt = System.nanoTime()
                database!!.captureWriteDao().insertCapture(
                    capture("soak-$index", now),
                    outbox("soak-$index", now),
                )
                latencies += (System.nanoTime() - startedAt) / NANOS_PER_MILLI
                now += POST_INTERVAL_MILLIS
                if ((index + 1) % POSTS_PER_HOUR == 0) {
                    maintenance.runScheduled(retention)
                }
            }
            maintenance.runScheduled(retention)

            assertEquals(1_000, database!!.retentionDao().sentRowCount())
            assertEquals(1_000, database!!.captureReadDao().captureCount())
            assertEquals(0, database!!.captureReadDao().unsentCount())
            assertEquals(6_200, tombstoneCount())
            assertTrue("p95 post->Room ${p95(latencies)}ms must be below 300ms", p95(latencies) < 300L)
            assertTrue("max post->Room ${latencies.max()}ms must be below 250ms", latencies.max() < 250L)
            Log.i(
                "RetentionTest",
                "RETENTION_SOAK_72H posts=$POSTS retained=1000 p95Ms=${p95(latencies)} " +
                    "maxMs=${latencies.max()} finalRows=${database!!.captureReadDao().captureCount()}",
            )
        }

    private suspend fun tombstoneCount(): Int =
        database!!.useReaderConnection { transactor ->
            transactor.usePrepared("SELECT COUNT(*) FROM capture_retention_tombstones") { statement ->
                statement.step()
                statement.getInt(0)
            }
        }

    private fun p95(values: List<Long>): Long {
        val sorted = values.sorted()
        return sorted[((sorted.size - 1) * 95 / 100).coerceAtLeast(0)]
    }

    private fun capture(
        eventId: String,
        capturedAt: Long,
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

    private fun outbox(
        eventId: String,
        now: Long,
    ) = DeliveryOutboxEntity(
        eventId = eventId,
        state = DeliveryState.SENT,
        sentAtEpochMillis = now,
        serverIngestId = "ingest-$eventId",
        serverReceivedAt = "2026-01-01T00:00:00Z",
        createdAtEpochMillis = now,
        updatedAtEpochMillis = now,
    )

    private fun inMemoryDatabase(): NfaCollectorDatabase =
        Room
            .inMemoryDatabaseBuilder(context, NfaCollectorDatabase::class.java)
            .build()

    private companion object {
        const val POSTS = 7_200
        const val POSTS_PER_HOUR = 100
        const val POST_INTERVAL_MILLIS = 36_000L
        const val START_EPOCH_MILLIS = 1_700_000_000_000L
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
