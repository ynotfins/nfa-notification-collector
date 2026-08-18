package com.nfaalerts.collector.delivery

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class DeliveryWorkManagerInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var workManager: WorkManager

    @Before
    fun setUp() {
        val executor = SynchronousExecutor()
        val configuration =
            Configuration
                .Builder()
                .setExecutor(executor)
                .setTaskExecutor(executor)
                .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, configuration)
        workManager = WorkManager.getInstance(context)
        workManager.cancelUniqueWork(DeliveryWorkScheduler.UNIQUE_WORK_NAME).result.get(5, TimeUnit.SECONDS)
    }

    @After
    fun tearDown() {
        workManager.cancelUniqueWork(DeliveryWorkScheduler.UNIQUE_WORK_NAME).result.get(5, TimeUnit.SECONDS)
    }

    @Test
    fun realUniqueWorkPreemptsSixHourDelayForNewDueNowRequest() {
        val now = System.currentTimeMillis()
        val scheduler = DeliveryWorkScheduler(context)
        scheduler.ensureScheduled(now + TimeUnit.HOURS.toMillis(6))
        val delayed = unfinished().single()

        scheduler.ensureScheduled(now)
        val immediate = unfinished().single()

        assertNotEquals(delayed.id, immediate.id)
        assertEquals(WorkInfo.State.ENQUEUED, immediate.state)
        val driver = WorkManagerTestInitHelper.getTestDriver(context)!!
        driver.setInitialDelayMet(immediate.id)
        assertEquals(1, unfinished().size)
    }

    private fun unfinished(): List<WorkInfo> =
        workManager
            .getWorkInfosForUniqueWork(DeliveryWorkScheduler.UNIQUE_WORK_NAME)
            .get(5, TimeUnit.SECONDS)
            .filterNot { it.state.isFinished }
}
