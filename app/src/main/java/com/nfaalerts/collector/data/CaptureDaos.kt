package com.nfaalerts.collector.data

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction

@Dao
abstract class CaptureWriteDao {
    @Insert
    protected abstract suspend fun insertCapturedRow(capture: CapturedNotificationEntity)

    @Insert
    protected abstract suspend fun insertInitialOutboxRow(outbox: DeliveryOutboxEntity)

    @Transaction
    open suspend fun insertCapture(
        capture: CapturedNotificationEntity,
        outbox: DeliveryOutboxEntity,
    ) {
        require(capture.eventId == outbox.eventId) { "Capture and outbox event IDs must match." }
        insertCapturedRow(capture)
        insertInitialOutboxRow(outbox)
    }
}

@Dao
interface CaptureReadDao {
    @Query("SELECT * FROM captured_notifications WHERE eventId = :eventId")
    suspend fun capture(eventId: String): CapturedNotificationEntity?

    @Query("SELECT * FROM delivery_outbox WHERE eventId = :eventId")
    suspend fun outbox(eventId: String): DeliveryOutboxEntity?

    @Query("SELECT COUNT(*) FROM captured_notifications")
    suspend fun captureCount(): Int

    @Query(
        """
        SELECT c.eventId, c.sourceId, c.capturedAtEpochMillis, o.state, o.attemptCount,
               o.lastHttpStatus, o.lastErrorCode, o.serverIngestId
        FROM captured_notifications c JOIN delivery_outbox o ON c.eventId = o.eventId
        ORDER BY c.capturedAtEpochMillis DESC, c.eventId DESC LIMIT :limit
        """,
    )
    suspend fun recentDeliveryInspection(limit: Int): List<DeliveryInspection>
}

data class DeliveryInspection(
    val eventId: String,
    val sourceId: String,
    val capturedAtEpochMillis: Long,
    val state: DeliveryState,
    val attemptCount: Int,
    val lastHttpStatus: Int?,
    val lastErrorCode: String?,
    val serverIngestId: String?,
)

@Dao
abstract class DeliveryDao {
    @Query(
        """
        SELECT eventId FROM delivery_outbox
        WHERE state IN ('PENDING', 'RETRY_WAIT')
          AND (nextAttemptAtEpochMillis IS NULL OR nextAttemptAtEpochMillis <= :nowEpochMillis)
        ORDER BY COALESCE(nextAttemptAtEpochMillis, createdAtEpochMillis), createdAtEpochMillis, eventId
        LIMIT 1
        """,
    )
    protected abstract suspend fun nextDueEventId(nowEpochMillis: Long): String?

    @Query(
        """
        UPDATE delivery_outbox
        SET state = 'SENDING',
            attemptCount = attemptCount + 1,
            nextAttemptAtEpochMillis = NULL,
            leaseOwner = :leaseOwner,
            leaseExpiresAtEpochMillis = :leaseExpiresAtEpochMillis,
            lastAttemptAtEpochMillis = :nowEpochMillis,
            pausedAtConfigRevision = NULL,
            updatedAtEpochMillis = :nowEpochMillis
        WHERE eventId = :eventId
          AND state IN ('PENDING', 'RETRY_WAIT')
          AND (nextAttemptAtEpochMillis IS NULL OR nextAttemptAtEpochMillis <= :nowEpochMillis)
        """,
    )
    protected abstract suspend fun guardClaim(
        eventId: String,
        leaseOwner: String,
        nowEpochMillis: Long,
        leaseExpiresAtEpochMillis: Long,
    ): Int

    @Query("SELECT * FROM delivery_outbox WHERE eventId = :eventId")
    protected abstract suspend fun outbox(eventId: String): DeliveryOutboxEntity?

    @Transaction
    open suspend fun claimDue(
        leaseOwner: String,
        nowEpochMillis: Long,
        leaseExpiresAtEpochMillis: Long,
    ): DeliveryOutboxEntity? {
        val eventId = nextDueEventId(nowEpochMillis) ?: return null
        return if (guardClaim(eventId, leaseOwner, nowEpochMillis, leaseExpiresAtEpochMillis) == 1) {
            outbox(eventId)
        } else {
            null
        }
    }

    @Query(
        """
        UPDATE delivery_outbox
        SET state = 'SENT', leaseOwner = NULL, leaseExpiresAtEpochMillis = NULL,
            nextAttemptAtEpochMillis = NULL, lastHttpStatus = 202, lastErrorCode = NULL,
            serverIngestId = :serverIngestId, serverReceivedAt = :serverReceivedAt,
            sentAtEpochMillis = :nowEpochMillis, updatedAtEpochMillis = :nowEpochMillis
        WHERE eventId = :eventId AND state = 'SENDING' AND leaseOwner = :leaseOwner
        """,
    )
    abstract suspend fun markSent(
        eventId: String,
        leaseOwner: String,
        nowEpochMillis: Long,
        serverIngestId: String,
        serverReceivedAt: String,
    ): Int

    @Query(
        """
        UPDATE delivery_outbox
        SET state = 'RETRY_WAIT', leaseOwner = NULL, leaseExpiresAtEpochMillis = NULL,
            nextAttemptAtEpochMillis = :nextAttemptAtEpochMillis,
            lastHttpStatus = :httpStatus, lastErrorCode = :errorCode,
            updatedAtEpochMillis = :nowEpochMillis
        WHERE eventId = :eventId AND state = 'SENDING' AND leaseOwner = :leaseOwner
        """,
    )
    abstract suspend fun markRetryWait(
        eventId: String,
        leaseOwner: String,
        nowEpochMillis: Long,
        nextAttemptAtEpochMillis: Long,
        httpStatus: Int?,
        errorCode: String,
    ): Int

    @Query(
        """
        UPDATE delivery_outbox
        SET state = 'PAUSED_AUTH', leaseOwner = NULL, leaseExpiresAtEpochMillis = NULL,
            nextAttemptAtEpochMillis = NULL, pausedAtConfigRevision = :configRevision,
            lastHttpStatus = :httpStatus, lastErrorCode = :errorCode,
            updatedAtEpochMillis = :nowEpochMillis
        WHERE eventId = :eventId AND state = 'SENDING' AND leaseOwner = :leaseOwner
        """,
    )
    abstract suspend fun markPausedAuth(
        eventId: String,
        leaseOwner: String,
        nowEpochMillis: Long,
        configRevision: Long,
        httpStatus: Int?,
        errorCode: String,
    ): Int

    @Query(
        """
        UPDATE delivery_outbox
        SET state = 'QUARANTINED', leaseOwner = NULL, leaseExpiresAtEpochMillis = NULL,
            nextAttemptAtEpochMillis = NULL, lastHttpStatus = :httpStatus,
            lastErrorCode = :errorCode, updatedAtEpochMillis = :nowEpochMillis
        WHERE eventId = :eventId AND state = 'SENDING' AND leaseOwner = :leaseOwner
        """,
    )
    abstract suspend fun markQuarantined(
        eventId: String,
        leaseOwner: String,
        nowEpochMillis: Long,
        httpStatus: Int?,
        errorCode: String,
    ): Int

    @Query(
        """
        UPDATE delivery_outbox
        SET state = 'RETRY_WAIT', leaseOwner = NULL, leaseExpiresAtEpochMillis = NULL,
            nextAttemptAtEpochMillis = :nowEpochMillis, lastErrorCode = 'STALE_SENDING_RECOVERED',
            updatedAtEpochMillis = :nowEpochMillis
        WHERE state = 'SENDING'
          AND leaseExpiresAtEpochMillis IS NOT NULL
          AND leaseExpiresAtEpochMillis < :nowEpochMillis
        """,
    )
    abstract suspend fun recoverStaleSending(nowEpochMillis: Long): Int

    @Query(
        """
        UPDATE delivery_outbox
        SET state = 'PENDING', nextAttemptAtEpochMillis = NULL,
            pausedAtConfigRevision = NULL, lastHttpStatus = NULL, lastErrorCode = NULL,
            updatedAtEpochMillis = :nowEpochMillis
        WHERE state = 'PAUSED_AUTH'
          AND pausedAtConfigRevision IS NOT NULL
          AND pausedAtConfigRevision != :currentConfigRevision
        """,
    )
    abstract suspend fun requeuePausedAuth(
        currentConfigRevision: Long,
        nowEpochMillis: Long,
    ): Int

    @Query(
        """
        SELECT MIN(dueAt) FROM (
          SELECT COALESCE(nextAttemptAtEpochMillis, createdAtEpochMillis) AS dueAt
          FROM delivery_outbox
          WHERE state IN ('PENDING', 'RETRY_WAIT')
          UNION ALL
          SELECT leaseExpiresAtEpochMillis AS dueAt
          FROM delivery_outbox
          WHERE state = 'SENDING' AND leaseExpiresAtEpochMillis IS NOT NULL
        )
        """,
    )
    abstract suspend fun nextDueAtEpochMillis(): Long?

    @Query(
        """
        UPDATE delivery_outbox
        SET state = 'PENDING', nextAttemptAtEpochMillis = NULL, leaseOwner = NULL,
            leaseExpiresAtEpochMillis = NULL, updatedAtEpochMillis = :nowEpochMillis
        WHERE eventId = :eventId AND state = 'RETRY_WAIT'
        """,
    )
    abstract suspend fun retryFromOperator(
        eventId: String,
        nowEpochMillis: Long,
    ): Int
}

data class RetentionCandidate(
    val eventId: String,
    val envelopeSha256: String,
    val serverIngestId: String?,
)

@Dao
abstract class RetentionDao {
    @Query(
        """
        SELECT c.eventId, c.envelopeSha256, o.serverIngestId
        FROM captured_notifications c
        JOIN delivery_outbox o ON o.eventId = c.eventId
        WHERE o.state = 'SENT'
          AND (
            COALESCE(o.sentAtEpochMillis, o.updatedAtEpochMillis) < :cutoffEpochMillis
            OR o.eventId IN (
              SELECT eventId FROM delivery_outbox
              WHERE state = 'SENT'
              ORDER BY COALESCE(sentAtEpochMillis, updatedAtEpochMillis) DESC, eventId DESC
              LIMIT -1 OFFSET :maxSentRows
            )
          )
        ORDER BY COALESCE(o.sentAtEpochMillis, o.updatedAtEpochMillis), o.eventId
        """,
    )
    protected abstract suspend fun candidates(
        cutoffEpochMillis: Long,
        maxSentRows: Int,
    ): List<RetentionCandidate>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertTombstone(tombstone: CaptureRetentionTombstoneEntity): Long

    @Query("DELETE FROM delivery_outbox WHERE eventId = :eventId AND state = 'SENT'")
    protected abstract suspend fun deleteSentOutbox(eventId: String): Int

    @Query("DELETE FROM captured_notifications WHERE eventId = :eventId")
    protected abstract suspend fun deleteCapture(eventId: String): Int

    @Query("SELECT * FROM capture_retention_tombstones WHERE eventId = :eventId")
    abstract suspend fun tombstone(eventId: String): CaptureRetentionTombstoneEntity?

    @Transaction
    open suspend fun pruneSent(
        cutoffEpochMillis: Long,
        maxSentRows: Int,
        nowEpochMillis: Long,
    ): Int {
        var deleted = 0
        candidates(cutoffEpochMillis, maxSentRows).forEach { candidate ->
            insertTombstone(
                CaptureRetentionTombstoneEntity(
                    eventId = candidate.eventId,
                    envelopeSha256 = candidate.envelopeSha256,
                    serverIngestId = candidate.serverIngestId,
                    retainedAtEpochMillis = nowEpochMillis,
                    reason = "SENT_RETENTION",
                ),
            )
            if (deleteSentOutbox(candidate.eventId) == 1) {
                deleted += deleteCapture(candidate.eventId)
            }
        }
        return deleted
    }
}

@Dao
internal abstract class DiagnosticsDao {
    @Insert
    protected abstract suspend fun insertRow(event: DiagnosticEventEntity)

    internal open suspend fun insertFromRepository(event: DiagnosticEventEntity) = insertRow(event)

    @Query("DELETE FROM diagnostic_events WHERE createdAtEpochMillis < :cutoffEpochMillis")
    protected abstract suspend fun deleteOlderThan(cutoffEpochMillis: Long): Int

    @Query(
        """
        DELETE FROM diagnostic_events WHERE diagnosticId IN (
          SELECT diagnosticId FROM diagnostic_events
          ORDER BY createdAtEpochMillis DESC, diagnosticId DESC
          LIMIT -1 OFFSET :maxRows
        )
        """,
    )
    protected abstract suspend fun deleteOverflow(maxRows: Int): Int

    @Query("SELECT * FROM diagnostic_events ORDER BY createdAtEpochMillis DESC, diagnosticId DESC LIMIT :limit")
    abstract suspend fun recent(limit: Int): List<DiagnosticEventEntity>

    @Transaction
    open suspend fun prune(
        cutoffEpochMillis: Long,
        maxRows: Int,
    ): Int = deleteOlderThan(cutoffEpochMillis) + deleteOverflow(maxRows)
}
