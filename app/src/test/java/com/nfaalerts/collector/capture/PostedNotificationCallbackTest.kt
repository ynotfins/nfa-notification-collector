package com.nfaalerts.collector.capture

import com.nfaalerts.collector.config.AllowlistSnapshot
import com.nfaalerts.collector.config.SelectionLoadState
import com.nfaalerts.collector.config.SourceSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class PostedNotificationCallbackTest {
    @Test
    fun `every selected posted callback dispatches a new event id even when identity repeats`() {
        val dispatcher = RecordingDispatcher()
        val ids = ArrayDeque(listOf("event-1", "event-2"))
        val callback =
            PostedNotificationCallback(
                allowlistProvider = { allowlist("com.example.selected") },
                selectionLoadStateProvider = { SelectionLoadState.Ready },
                eventIdFactory = { ids.removeFirst() },
                clock = { 1234L },
                dispatcher = dispatcher,
            )
        val notificationHandle = Any()
        val input = postedInput("com.example.selected", notificationHandle)

        assertTrue(callback.onNotificationPosted(input))
        assertTrue(callback.onNotificationPosted(input))

        assertEquals(2, dispatcher.requests.size)
        assertNotEquals(dispatcher.requests[0].eventId, dispatcher.requests[1].eventId)
        assertSame(notificationHandle, dispatcher.requests[0].notificationHandle)
        assertEquals("android-key", dispatcher.requests[0].identity.key)
    }

    @Test
    fun `unselected callback returns without creating ids or dispatching`() {
        var idCalls = 0
        val dispatcher = RecordingDispatcher()
        val callback =
            PostedNotificationCallback(
                allowlistProvider = { allowlist("com.example.selected") },
                selectionLoadStateProvider = { SelectionLoadState.Ready },
                eventIdFactory = {
                    idCalls += 1
                    "unexpected"
                },
                clock = { 1234L },
                dispatcher = dispatcher,
            )

        assertFalse(callback.onNotificationPosted(postedInput("com.example.other", Any())))
        assertEquals(0, idCalls)
        assertTrue(dispatcher.requests.isEmpty())
    }

    @Test
    fun `callbacks buffered during initialization drain selected notifications once in arrival order`() {
        var state: SelectionLoadState = SelectionLoadState.Loading
        val dispatcher = RecordingDispatcher()
        val status = ListenerStatusRepository(clock = { 1L })
        val ids = AtomicInteger()
        val callback =
            PostedNotificationCallback(
                allowlistProvider = { allowlist("com.example.selected") },
                selectionLoadStateProvider = { state },
                eventIdFactory = { "event-${ids.getAndIncrement()}" },
                clock = { 1234L },
                dispatcher = dispatcher,
                initializationDiagnostics = status,
            )

        assertTrue(callback.onNotificationPosted(postedInput("com.example.selected", Any()).copy(key = "first")))
        assertTrue(callback.onNotificationPosted(postedInput("com.example.other", Any()).copy(key = "discard")))
        assertTrue(callback.onNotificationPosted(postedInput("com.example.selected", Any()).copy(key = "second")))
        assertTrue(dispatcher.requests.isEmpty())

        state = SelectionLoadState.Ready
        callback.onSelectionLoadCompleted()

        assertEquals(listOf("first", "second"), dispatcher.requests.map { it.identity.key })
        val eventIds = dispatcher.requests.map { it.eventId }.toSet()
        assertEquals(2, eventIds.size)
        assertEquals(3L, status.state.value.initializationBufferedCount)
        assertEquals(0, status.state.value.initializationBufferDepth)
    }

    @Test
    fun `invalid configuration completes initialization with a fail closed empty allowlist`() {
        var state: SelectionLoadState = SelectionLoadState.Loading
        val dispatcher = RecordingDispatcher()
        val callback =
            PostedNotificationCallback(
                allowlistProvider = { AllowlistSnapshot.EMPTY },
                selectionLoadStateProvider = { state },
                eventIdFactory = { "event" },
                clock = { 1L },
                dispatcher = dispatcher,
            )

        assertTrue(callback.onNotificationPosted(postedInput("com.example.selected", Any())))
        state = SelectionLoadState.Invalid("INVALID_CONFIG")
        callback.onSelectionLoadCompleted()

        assertTrue(dispatcher.requests.isEmpty())
    }

    @Test
    fun `initialization buffer bounds overflow without retaining notification content`() {
        var state: SelectionLoadState = SelectionLoadState.Loading
        val dispatcher = RecordingDispatcher()
        val status = ListenerStatusRepository(clock = { 1L })
        val ids = AtomicInteger()
        val callback =
            PostedNotificationCallback(
                allowlistProvider = { allowlist("com.example.selected") },
                selectionLoadStateProvider = { state },
                eventIdFactory = { "event-${ids.getAndIncrement()}" },
                clock = { 1L },
                dispatcher = dispatcher,
                initializationDiagnostics = status,
                initializationBufferCapacity = 2,
            )

        assertTrue(callback.onNotificationPosted(postedInput("com.example.selected", Any()).copy(key = "one")))
        assertTrue(callback.onNotificationPosted(postedInput("com.example.selected", Any()).copy(key = "two")))
        assertFalse(callback.onNotificationPosted(postedInput("com.example.selected", Any()).copy(key = "overflow")))
        assertEquals(1L, status.state.value.initializationOverflowCount)
        assertEquals(2, status.state.value.initializationBufferDepth)

        state = SelectionLoadState.Ready
        callback.onSelectionLoadCompleted()

        assertEquals(listOf("one", "two"), dispatcher.requests.map { it.identity.key })
    }

    @Test
    fun `concurrent callbacks are buffered once and drained through the completed allowlist`() {
        var state: SelectionLoadState = SelectionLoadState.Loading
        val dispatcher = RecordingDispatcher()
        val ids = AtomicInteger()
        val callback =
            PostedNotificationCallback(
                allowlistProvider = { allowlist("com.example.selected") },
                selectionLoadStateProvider = { state },
                eventIdFactory = { "event-${ids.getAndIncrement()}" },
                clock = { 1L },
                dispatcher = dispatcher,
                initializationBufferCapacity = 64,
            )
        val executor = Executors.newFixedThreadPool(8)
        try {
            val callbacks =
                (0 until 32).map { index ->
                    executor.submit<Boolean> {
                        callback.onNotificationPosted(
                            postedInput("com.example.selected", Any()).copy(key = "key-$index"),
                        )
                    }
                }
            assertTrue(callbacks.all { it.get(5, TimeUnit.SECONDS) })
        } finally {
            executor.shutdownNow()
        }

        state = SelectionLoadState.Ready
        callback.onSelectionLoadCompleted()

        assertEquals(32, dispatcher.requests.size)
        assertEquals((0 until 32).map { "key-$it" }.toSet(), dispatcher.requests.map { it.identity.key }.toSet())
    }

    private fun postedInput(
        packageName: String,
        handle: Any,
    ) = LightweightPostedNotification(
        packageName = packageName,
        key = "android-key",
        notificationId = 7,
        tag = "tag",
        postTimeEpochMillis = 1000L,
        uid = 42,
        userId = 0,
        isOngoing = false,
        isClearable = true,
        notificationHandle = handle,
    )

    private fun allowlist(packageName: String) =
        AllowlistSnapshot.from(
            listOf(
                SourceSelection(
                    packageName = packageName,
                    appLabel = "Selected",
                    sourceId = "example",
                    enabled = true,
                    bnnMappingConfirmed = false,
                    rawTextOrder = RawTextField.DEFAULT_ORDER,
                ),
            ),
        )

    private class RecordingDispatcher : CaptureDispatcher {
        val requests = mutableListOf<DispatchedNotification>()

        override fun dispatch(request: DispatchedNotification) {
            requests += request
        }
    }
}
