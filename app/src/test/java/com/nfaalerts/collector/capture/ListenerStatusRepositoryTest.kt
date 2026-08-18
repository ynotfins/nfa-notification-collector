package com.nfaalerts.collector.capture

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ListenerStatusRepositoryTest {
    @Test
    fun `listener lifecycle and dispatch diagnostics are observable as state flow`() {
        val status = ListenerStatusRepository(clock = { 10L })

        status.onListenerConnected()
        status.onDispatchStarted()
        status.onDispatchFinished()
        status.onListenerDisconnected()

        assertFalse(status.state.value.connected)
        assertEquals(10L, status.state.value.lastConnectedAtEpochMillis)
        assertEquals(10L, status.state.value.lastDisconnectedAtEpochMillis)
        assertEquals(1L, status.state.value.dispatchedCount)
        assertEquals(0, status.state.value.activeWorkers)
    }

    @Test
    fun `cancelled application scope rejects dispatch without running processor`() {
        val status = ListenerStatusRepository(clock = { 1L })
        val job = SupervisorJob().apply { cancel() }
        var processed = false
        val dispatcher =
            CoroutineCaptureDispatcher(
                scope = CoroutineScope(job + Dispatchers.Unconfined),
                diagnostics = status,
                processor = { processed = true },
            )

        dispatcher.dispatch(request())

        assertFalse(processed)
        assertEquals(1L, status.state.value.dispatchRejectedCount)
    }

    @Test
    fun `worker exception is contained and recorded by type only`() {
        val status = ListenerStatusRepository(clock = { 1L })
        val dispatcher =
            CoroutineCaptureDispatcher(
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
                diagnostics = status,
                processor = { error("private message") },
            )

        dispatcher.dispatch(request())

        assertEquals(1L, status.state.value.workerFailureCount)
        assertEquals(IllegalStateException::class.java.name, status.state.value.lastWorkerFailureType)
        assertTrue(status.state.value.activeWorkers == 0)
    }

    @Test
    fun `concurrent starts and finishes publish an atomic zero final backlog and exact maximum`() {
        val workers = 32
        val status = ListenerStatusRepository(clock = { 1L })
        val barrier = CyclicBarrier(workers)
        val executor = Executors.newFixedThreadPool(workers)
        try {
            val futures =
                (0 until workers).map {
                    executor.submit {
                        status.onDispatchStarted()
                        barrier.await(5, TimeUnit.SECONDS)
                        status.onDispatchFinished()
                    }
                }
            futures.forEach { it.get(10, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }

        assertEquals(0, status.state.value.activeWorkers)
        assertEquals(workers, status.state.value.maximumObservedBacklog)
        assertEquals(workers.toLong(), status.state.value.dispatchedCount)
    }

    private fun request() =
        DispatchedNotification(
            eventId = "event",
            capturedAtEpochMillis = 1L,
            identity =
                NotificationIdentity(
                    "package",
                    "key",
                    1,
                    null,
                    1L,
                    1,
                    0,
                    false,
                    true,
                    null,
                    null,
                ),
            source =
                com.nfaalerts.collector.config.SourceSelection(
                    "package",
                    "Package",
                    "other",
                    true,
                    false,
                    RawTextField.DEFAULT_ORDER,
                ),
            notificationHandle = Any(),
        )
}
