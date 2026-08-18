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
    fun runningWorkerRecordsEarlierKickAndAppendsFollowUpWithoutReplacingItself() {
        val calls = mutableListOf<Pair<ExistingWorkPolicy, OneTimeWorkRequest>>()
        val scheduler =
            DeliveryWorkScheduler(
                enqueuer = UniqueWorkEnqueuer { _, policy, request -> calls += policy to request },
                clock = { 1_000L },
            )

        scheduler.ensureScheduled(21_601_000L)
        scheduler.onWorkerStarted()
        scheduler.ensureScheduled(1_000L)
        scheduler.ensureScheduled(601_000L)
        scheduler.onWorkerFinished(601_000L)

        assertEquals(2, calls.size)
        assertEquals(ExistingWorkPolicy.REPLACE, calls.first().first)
        val followUp = calls.last()
        assertEquals(ExistingWorkPolicy.APPEND_OR_REPLACE, followUp.first)
        assertEquals(
            0L,
            followUp.second.workSpec.initialDelay,
        )
    }
}
