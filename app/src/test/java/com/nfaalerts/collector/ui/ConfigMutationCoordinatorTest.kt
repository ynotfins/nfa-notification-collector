package com.nfaalerts.collector.ui

import com.nfaalerts.collector.config.CollectorConfigCodec
import com.nfaalerts.collector.config.ConfigSaveResult
import com.nfaalerts.collector.config.ConfigValidationError
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class ConfigMutationCoordinatorTest {
    @Test
    fun `persisted config invalidates verification reloads sources then requeues`() =
        runBlocking {
            val calls = mutableListOf<String>()
            val coordinator = coordinator(calls)

            val outcome = coordinator.save { ConfigSaveResult.Saved(CollectorConfigCodec().defaultDocument()) }

            assertEquals(ConfigMutationOutcome.Saved, outcome)
            assertEquals(listOf("persist", "invalidate", "sources", "ui", "requeue"), calls)
        }

    @Test
    fun `source reload failure remains persisted partial success and skips requeue`() =
        runBlocking {
            val calls = mutableListOf<String>()
            val coordinator = coordinator(calls, failSourceReload = true)

            val outcome = coordinator.save { ConfigSaveResult.Saved(CollectorConfigCodec().defaultDocument()) }

            assertEquals(ConfigMutationOutcome.SavedFollowUpPending("SOURCE_RELOAD_PENDING"), outcome)
            assertEquals(listOf("persist", "invalidate", "sources"), calls)
        }

    @Test
    fun `requeue failure remains persisted partial success`() =
        runBlocking {
            val calls = mutableListOf<String>()
            val coordinator = coordinator(calls, failRequeue = true)

            val outcome = coordinator.save { ConfigSaveResult.Saved(CollectorConfigCodec().defaultDocument()) }

            assertEquals(ConfigMutationOutcome.SavedFollowUpPending("DELIVERY_REQUEUE_PENDING"), outcome)
            assertEquals(listOf("persist", "invalidate", "sources", "ui", "requeue"), calls)
        }

    @Test
    fun `rejected config performs no follow up`() =
        runBlocking {
            val calls = mutableListOf<String>()
            val error = ConfigValidationError("/deviceId", "DEVICE_ID_FORMAT", "Device ID is invalid.")
            val coordinator = coordinator(calls)

            val outcome = coordinator.save { ConfigSaveResult.Rejected(listOf(error)) }

            assertEquals(ConfigMutationOutcome.Rejected(listOf(error)), outcome)
            assertEquals(listOf("persist"), calls)
        }

    private fun coordinator(
        calls: MutableList<String>,
        failSourceReload: Boolean = false,
        failRequeue: Boolean = false,
    ) = ConfigMutationCoordinator(
        invalidateVerification = { calls += "invalidate" },
        reloadSources = {
            calls += "sources"
            if (failSourceReload) error("reload")
        },
        reloadUi = { calls += "ui" },
        requeue = {
            calls += "requeue"
            if (failRequeue) error("requeue")
        },
        onPersist = { calls += "persist" },
    )
}
