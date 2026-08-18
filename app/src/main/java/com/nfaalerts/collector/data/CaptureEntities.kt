package com.nfaalerts.collector.data

import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey

@Entity(tableName = "captured_notifications")
data class CapturedNotificationEntity(
    @PrimaryKey val eventId: String,
    val packageName: String,
    val sourceId: String,
    val notificationKey: String,
    val notificationId: Int,
    val notificationTag: String?,
    val postTimeEpochMillis: Long,
    val capturedAtEpochMillis: Long,
    val rawText: String?,
    val rawCandidatesJson: String,
    val envelopeJson: String,
    val envelopeSha256: String,
    val envelopeUtf8Bytes: Int,
)

@Entity(
    tableName = "delivery_outbox",
    foreignKeys = [
        ForeignKey(
            entity = CapturedNotificationEntity::class,
            parentColumns = ["eventId"],
            childColumns = ["eventId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [Index("state", "nextAttemptAtEpochMillis")],
)
data class DeliveryOutboxEntity(
    @PrimaryKey val eventId: String,
    val state: DeliveryState,
    val attemptCount: Int = 0,
    val nextAttemptAtEpochMillis: Long? = null,
    val leaseOwner: String? = null,
    val leaseExpiresAtEpochMillis: Long? = null,
    val lastAttemptAtEpochMillis: Long? = null,
    val pausedAtConfigRevision: Long? = null,
    val sentAtEpochMillis: Long? = null,
    val lastHttpStatus: Int? = null,
    val lastErrorCode: String? = null,
    val serverIngestId: String? = null,
    val serverReceivedAt: String? = null,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)

@Entity(tableName = "capture_retention_tombstones")
data class CaptureRetentionTombstoneEntity(
    @PrimaryKey val eventId: String,
    val envelopeSha256: String,
    val serverIngestId: String?,
    val retainedAtEpochMillis: Long,
    val reason: String,
)

@Entity(tableName = "diagnostic_events", indices = [Index("createdAtEpochMillis")])
data class DiagnosticEventEntity(
    @PrimaryKey val diagnosticId: String,
    val createdAtEpochMillis: Long,
    val eventCode: String,
    val safeDetailsJson: String,
)
