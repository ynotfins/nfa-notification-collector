package com.nfaalerts.collector

import android.app.Application
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nfaalerts.collector.data.CapturedNotificationEntity
import com.nfaalerts.collector.data.DeliveryOutboxEntity
import com.nfaalerts.collector.data.DeliveryState
import com.nfaalerts.collector.data.NfaCollectorDatabase
import com.nfaalerts.collector.delivery.DeliveryWorkScheduler
import com.nfaalerts.collector.delivery.UniqueWorkEnqueuer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppContainerRecoveryInstrumentedTest {
    private val application = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun startupBeforeAndAfterLeaseExpirySchedulesThenRecoversSendingRow() =
        runBlocking {
            val database = Room.inMemoryDatabaseBuilder(application, NfaCollectorDatabase::class.java).build()
            try {
                database.captureWriteDao().insertCapture(capture(), outbox())
                database.deliveryDao().claimDue("old-process", 0L, 600_000L)
                val beforeCalls = mutableListOf<androidx.work.OneTimeWorkRequest>()
                val beforeScheduler =
                    DeliveryWorkScheduler(
                        UniqueWorkEnqueuer { _, _, request -> beforeCalls += request },
                        clock = { 599_999L },
                    )
                val before =
                    AppContainer(
                        application = application,
                        databaseFactory = { database },
                        deliveryScheduler = beforeScheduler,
                        clock = { 599_999L },
                    )

                before.recoverDeliveryOnStartup()

                assertEquals(DeliveryState.SENDING, database.captureReadDao().outbox(EVENT_ID)?.state)
                assertEquals(1L, beforeCalls.single().workSpec.initialDelay)

                val afterCalls = mutableListOf<androidx.work.OneTimeWorkRequest>()
                val afterScheduler =
                    DeliveryWorkScheduler(
                        UniqueWorkEnqueuer { _, _, request -> afterCalls += request },
                        clock = { 600_001L },
                    )
                val after =
                    AppContainer(
                        application = application,
                        databaseFactory = { database },
                        deliveryScheduler = afterScheduler,
                        clock = { 600_001L },
                    )

                after.recoverDeliveryOnStartup()

                assertEquals(DeliveryState.RETRY_WAIT, database.captureReadDao().outbox(EVENT_ID)?.state)
                assertEquals(0L, afterCalls.single().workSpec.initialDelay)
            } finally {
                database.close()
            }
        }

    private fun capture() =
        CapturedNotificationEntity(
            eventId = EVENT_ID,
            packageName = "com.example.bnn",
            sourceId = "bnn",
            notificationKey = "key",
            notificationId = 1,
            notificationTag = null,
            postTimeEpochMillis = 0L,
            capturedAtEpochMillis = 0L,
            rawText = "raw",
            rawCandidatesJson = "{}",
            envelopeJson = "{}",
            envelopeSha256 = "sha",
            envelopeUtf8Bytes = 2,
        )

    private fun outbox() =
        DeliveryOutboxEntity(
            eventId = EVENT_ID,
            state = DeliveryState.PENDING,
            createdAtEpochMillis = 0L,
            updatedAtEpochMillis = 0L,
        )

    private companion object {
        const val EVENT_ID = "startup-event"
    }
}
