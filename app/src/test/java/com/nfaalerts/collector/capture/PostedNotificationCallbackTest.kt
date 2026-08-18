package com.nfaalerts.collector.capture

import com.nfaalerts.collector.config.AllowlistSnapshot
import com.nfaalerts.collector.config.SourceSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PostedNotificationCallbackTest {
    @Test
    fun `every selected posted callback dispatches a new event id even when identity repeats`() {
        val dispatcher = RecordingDispatcher()
        val ids = ArrayDeque(listOf("event-1", "event-2"))
        val callback =
            PostedNotificationCallback(
                allowlistProvider = { allowlist("com.example.selected") },
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
