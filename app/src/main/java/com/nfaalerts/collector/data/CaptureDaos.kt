package com.nfaalerts.collector.data

import androidx.room3.Dao
import androidx.room3.Insert
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
}
