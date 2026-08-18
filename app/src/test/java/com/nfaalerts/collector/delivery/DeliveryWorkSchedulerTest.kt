package com.nfaalerts.collector.delivery

import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeliveryWorkSchedulerTest {
    @Test
    fun earlierKickReplacesObsoleteDelayedUniqueDrain() {
        val calls = mutableListOf<Triple<String, ExistingWorkPolicy, OneTimeWorkRequest>>()
        val scheduler =
            DeliveryWorkScheduler(
                enqueuer = UniqueWorkEnqueuer { name, policy, request -> calls += Triple(name, policy, request) },
                clock = { 1_000L },
            )

        scheduler.ensureScheduled(21_601_000L)
        scheduler.ensureScheduled(1_000L)

        assertEquals(2, calls.size)
        assertTrue(calls.all { it.first == DeliveryWorkScheduler.UNIQUE_WORK_NAME })
        assertTrue(calls.all { it.second == ExistingWorkPolicy.REPLACE })
        assertEquals(21_600_000L, calls[0].third.workSpec.initialDelay)
        assertEquals(0L, calls[1].third.workSpec.initialDelay)
        assertTrue(calls.all { DeliveryDrainWorker::class.java.name in it.third.tags })
    }

    @Test
    fun cancelledActiveClaimCannotReplaceNewImmediateWorkWithLaterLeaseWake() {
        val calls = mutableListOf<OneTimeWorkRequest>()
        val scheduler =
            DeliveryWorkScheduler(
                enqueuer = UniqueWorkEnqueuer { _, _, request -> calls += request },
                clock = { 1_000L },
            )

        scheduler.ensureScheduled(1_000L)
        scheduler.onWorkerStarted()
        scheduler.ensureScheduled(1_000L)
        scheduler.ensureScheduled(601_000L)

        assertEquals(2, calls.size)
        assertEquals(0L, calls.last().workSpec.initialDelay)
    }
}
