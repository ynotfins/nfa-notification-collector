package com.nfaalerts.collector.delivery

import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeliveryWorkSchedulerTest {
    @Test
    fun everyKickUsesTheSameKeepProtectedUniqueDrain() {
        val calls = mutableListOf<Triple<String, ExistingWorkPolicy, OneTimeWorkRequest>>()
        val scheduler =
            DeliveryWorkScheduler(
                enqueuer = UniqueWorkEnqueuer { name, policy, request -> calls += Triple(name, policy, request) },
                clock = { 1_000L },
            )

        scheduler.ensureScheduled(1_000L)
        scheduler.ensureScheduled(5_000L)

        assertEquals(2, calls.size)
        assertTrue(calls.all { it.first == DeliveryWorkScheduler.UNIQUE_WORK_NAME })
        assertTrue(calls.all { it.second == ExistingWorkPolicy.KEEP })
        assertTrue(calls.all { DeliveryDrainWorker::class.java.name in it.third.tags })
    }
}
