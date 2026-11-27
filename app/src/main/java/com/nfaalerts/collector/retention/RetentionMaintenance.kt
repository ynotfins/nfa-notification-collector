package com.nfaalerts.collector.retention

import com.nfaalerts.collector.config.RetentionConfig
import com.nfaalerts.collector.data.NfaCollectorDatabase
import com.nfaalerts.collector.data.RetentionDao
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

data class StorageSnapshot(
    val capturedRows: Long,
    val unsentRows: Long,
    val databaseBytes: Long,
) {
    fun exceedsPressure(retention: RetentionConfig): Boolean =
        capturedRows >= retention.pressureMaxRows || databaseBytes >= retention.pressureMaxBytes
}

data class RetentionOutcome(
    val deletedSentRows: Int,
    val batches: Int,
    val durationMillis: Long,
)

data class PressureRelease(
    val before: StorageSnapshot,
    val after: StorageSnapshot,
    val deletedSentRows: Int,
    val rowsTriggered: Boolean,
    val bytesTriggered: Boolean,
)

/**
 * Runs sent-row retention and storage-pressure pruning off the listener callback thread.
 * Every delete is a bounded transaction of [RetentionDao.DEFAULT_PURGE_BATCH_SIZE] rows so
 * capture writes can interleave between batches.
 */
class RetentionMaintenance(
    private val database: NfaCollectorDatabase,
    private val databaseSizeBytes: () -> Long,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    suspend fun runScheduled(retention: RetentionConfig): RetentionOutcome =
        withContext(ioDispatcher) {
            val startedAt = nanoTime()
            val now = clock()
            val cutoff = now - TimeUnit.DAYS.toMillis(retention.sentDays.toLong())
            var batches = 0
            val deleted =
                database.retentionDao().pruneSentBatched(
                    cutoffEpochMillis = cutoff,
                    maxSentRows = retention.maxSentRows,
                    nowEpochMillis = now,
                    batchSize = RetentionDao.DEFAULT_PURGE_BATCH_SIZE,
                    onBatch = { batches++ },
                )
            RetentionOutcome(
                deletedSentRows = deleted,
                batches = batches,
                durationMillis = (nanoTime() - startedAt) / NANOS_PER_MILLI,
            )
        }

    suspend fun releasePressureIfNeeded(retention: RetentionConfig): PressureRelease? =
        withContext(ioDispatcher) {
            val before = snapshotNow()
            if (!before.exceedsPressure(retention)) return@withContext null

            val now = clock()
            val rowsTriggered = before.capturedRows >= retention.pressureMaxRows
            val bytesTriggered = before.databaseBytes >= retention.pressureMaxBytes
            var deleted = 0
            if (rowsTriggered) {
                val targetTotal =
                    (retention.pressureMaxRows * PRESSURE_TARGET_PERCENT / 100).coerceAtLeast(1)
                val keepSent = (targetTotal - before.unsentRows).coerceAtLeast(0).toInt()
                deleted +=
                    database.retentionDao().pruneOldestSent(
                        keepRows = keepSent,
                        nowEpochMillis = now,
                    )
            }
            if (bytesTriggered) {
                val sentRows = database.retentionDao().sentRowCount()
                val extraLimit =
                    (retention.pressureMaxRows / PRESSURE_BYTES_SLICE_DIVISOR)
                        .coerceAtLeast(RetentionDao.DEFAULT_PURGE_BATCH_SIZE)
                        .coerceAtMost(PRESSURE_BYTES_SLICE_MAX_ROWS)
                val keepRows = (sentRows - extraLimit).coerceAtLeast(0)
                deleted +=
                    database.retentionDao().pruneOldestSent(
                        keepRows = keepRows,
                        nowEpochMillis = now,
                    )
            }
            PressureRelease(
                before = before,
                after = snapshotNow(),
                deletedSentRows = deleted,
                rowsTriggered = rowsTriggered,
                bytesTriggered = bytesTriggered,
            )
        }

    suspend fun snapshot(): StorageSnapshot = withContext(ioDispatcher) { snapshotNow() }

    private suspend fun snapshotNow(): StorageSnapshot =
        StorageSnapshot(
            capturedRows = database.captureReadDao().captureCount().toLong(),
            unsentRows = database.captureReadDao().unsentCount().toLong(),
            databaseBytes = databaseSizeBytes(),
        )

    companion object {
        const val PRESSURE_TARGET_PERCENT = 75
        const val PRESSURE_BYTES_SLICE_DIVISOR = 10
        const val PRESSURE_BYTES_SLICE_MAX_ROWS = 10_000
        private const val NANOS_PER_MILLI = 1_000_000L
    }
}
