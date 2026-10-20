package com.nfaalerts.collector.capture

import com.nfaalerts.collector.config.AllowlistSnapshot
import com.nfaalerts.collector.config.SelectionLoadState
import com.nfaalerts.collector.config.SourceSelection
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class CaptureReliabilityTest {
    @Test
    fun `unselected posts are recorded as allowlist rejects and are not dispatched`() {
        val drops = RecordingDropLedger()
        val dispatcher = RecordingDispatcher()
        val callback = readyCallback(dispatcher, drops)

        assertFalse(callback.onNotificationPosted(posted("com.example.other")))

        assertTrue(dispatcher.requests.isEmpty())
        assertEquals(CaptureDropReason.ALLOWLIST_REJECT, drops.reasons.single())
    }

    @Test
    fun `startup buffer overflow is recorded and does not keep the notification`() {
        val drops = RecordingDropLedger()
        val dispatcher = RecordingDispatcher()
        var state: SelectionLoadState = SelectionLoadState.Loading
        val callback =
            PostedNotificationCallback(
                allowlistProvider = { allowlist("com.example.selected") },
                selectionLoadStateProvider = { state },
                eventIdFactory = { "event" },
                clock = { 1L },
                dispatcher = dispatcher,
                initializationBufferCapacity = 1,
                dropLedger = drops,
            )

        assertTrue(callback.onNotificationPosted(posted("com.example.selected").copy(key = "kept")))
        assertFalse(callback.onNotificationPosted(posted("com.example.selected").copy(key = "dropped")))
        assertEquals(CaptureDropReason.BUFFER_OVERFLOW, drops.reasons.single())

        state = SelectionLoadState.Ready
        callback.onSelectionLoadCompleted()
        assertEquals(listOf("kept"), dispatcher.requests.map { it.identity.key })
    }

    @Test
    fun `live repeats stay distinct and catch-up skips only a saved package key and post time`() =
        runBlocking {
            val dispatcher = RecordingDispatcher()
            val callback = readyCallback(dispatcher, RecordingDropLedger())
            val input = posted("com.example.selected")
            assertTrue(callback.onNotificationPosted(input))
            assertTrue(callback.onNotificationPosted(input))
            assertEquals(2, dispatcher.requests.size)
            assertEquals(2, dispatcher.requests.map { it.eventId }.toSet().size)

            val saved = mutableSetOf("com.example.selected|android-key|1000")
            val drops = RecordingDropLedger()
            val catchUp =
                ActiveNotificationCatchUp(
                    alreadyCaptured = { packageName, key, postTime -> "$packageName|$key|$postTime" in saved },
                    onMissing = callback::onNotificationPosted,
                    dropLedger = drops,
                )
            val result = catchUp.reconcile(listOf(input.copy(capturePath = CapturePath.SHADE)))

            assertEquals(0, result.imported)
            assertEquals(1, result.skippedExisting)
            assertEquals(CaptureDropReason.CATCH_UP_ALREADY_SAVED, drops.reasons.single())
            assertEquals(2, dispatcher.requests.size)
        }

    @Test
    fun `dispatch does not return until the row is durable`() {
        val disk = mutableListOf<String>()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        var returned = false
        val dispatcher =
            DurableCaptureDispatcher(
                processor = { request ->
                    started.countDown()
                    assertTrue(release.await(5, TimeUnit.SECONDS))
                    disk += request.eventId
                },
                diagnostics = ListenerStatusRepository(clock = { 1L }),
            )
        val thread =
            Thread {
                dispatcher.dispatch(request("event-1"))
                returned = true
            }
        thread.start()
        assertTrue(started.await(5, TimeUnit.SECONDS))
        assertFalse(returned)
        release.countDown()
        thread.join(5_000)
        assertTrue(returned)
        val memoryQueue = mutableListOf("event-1")
        memoryQueue.clear()
        assertEquals(listOf("event-1"), disk)
    }

    @Test
    fun `autogroup summary is a recorded skip and a child is not`() {
        val summary = NotificationFlags.GROUP_SUMMARY or NotificationFlags.LOCAL_ONLY
        assertEquals(
            CaptureDropReason.AUTOGROUP_SUMMARY,
            autogroupAdmission(summary, NotificationFlags.GROUP_SUMMARY, NotificationFlags.LOCAL_ONLY),
        )
        assertNull(autogroupAdmission(NotificationFlags.GROUP_SUMMARY, NotificationFlags.GROUP_SUMMARY, NotificationFlags.LOCAL_ONLY))
    }

    @Test
    fun `clone failure is recorded and the original notification is kept for the save`() {
        var recorded = false
        val original = Any()
        val kept =
            notificationAfterCopy(
                cloneSucceeded = false,
                cloned = null,
                original = original,
                onCloneFailure = { recorded = true },
            )
        assertTrue(recorded)
        assertTrue(kept === original)
        val copy = Any()
        val cloned =
            notificationAfterCopy(
                cloneSucceeded = true,
                cloned = copy,
                original = original,
                onCloneFailure = { recorded = false },
            )
        assertTrue(recorded)
        assertTrue(cloned === copy)
    }

    @Test
    fun `sensitive empty notifications are marked and ordinary empty notifications are not`() {
        assertEquals(
            SENSITIVE_REDACTED,
            classifySensitiveRedaction(
                visibility = VISIBILITY_SECRET,
                lockscreenVisibilityOverride = null,
                channelLockscreenVisibility = null,
                visibleTextBlank = true,
            ),
        )
        assertNull(
            classifySensitiveRedaction(
                visibility = 0,
                lockscreenVisibilityOverride = null,
                channelLockscreenVisibility = null,
                visibleTextBlank = true,
            ),
        )
        assertNull(
            classifySensitiveRedaction(
                visibility = VISIBILITY_SECRET,
                lockscreenVisibilityOverride = null,
                channelLockscreenVisibility = null,
                visibleTextBlank = false,
            ),
        )
    }

    @Test
    fun `ranking is stored on the dispatched identity`() {
        val dispatcher = RecordingDispatcher()
        val ranking = RankingSnapshot(importance = 3, suppressedVisualEffects = 0, ambient = false)
        val callback = readyCallback(dispatcher, RecordingDropLedger())

        assertTrue(callback.onNotificationPosted(posted("com.example.selected").copy(ranking = ranking)))

        assertEquals(ranking, dispatcher.requests.single().identity.ranking)
    }

    private fun readyCallback(
        dispatcher: CaptureDispatcher,
        drops: CaptureDropLedger,
    ): PostedNotificationCallback {
        val ids = ArrayDeque(listOf("event-1", "event-2", "event-3"))
        return PostedNotificationCallback(
            allowlistProvider = { allowlist("com.example.selected") },
            selectionLoadStateProvider = { SelectionLoadState.Ready },
            eventIdFactory = { ids.removeFirst() },
            clock = { 50L },
            dispatcher = dispatcher,
            dropLedger = drops,
        )
    }

    private fun posted(packageName: String) =
        LightweightPostedNotification(
            packageName = packageName,
            key = "android-key",
            notificationId = 7,
            tag = "tag",
            postTimeEpochMillis = 1000L,
            uid = 42,
            userId = 0,
            isOngoing = false,
            isClearable = true,
            notificationHandle = Any(),
        )

    private fun request(eventId: String) =
        DispatchedNotification(
            eventId = eventId,
            capturedAtEpochMillis = 50L,
            identity =
                NotificationIdentity(
                    packageName = "com.example.selected",
                    key = "android-key",
                    notificationId = 7,
                    tag = "tag",
                    postTimeEpochMillis = 1000L,
                    uid = 42,
                    userId = 0,
                    isOngoing = false,
                    isClearable = true,
                ),
            source =
                SourceSelection(
                    packageName = "com.example.selected",
                    appLabel = "Selected",
                    sourceId = "example",
                    enabled = true,
                    bnnMappingConfirmed = false,
                    rawTextOrder = RawTextField.DEFAULT_ORDER,
                ),
            notificationHandle = Any(),
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

    private class RecordingDropLedger : CaptureDropLedger {
        val reasons = mutableListOf<CaptureDropReason>()

        override fun onDropped(
            reason: CaptureDropReason,
            packageName: String,
            capturePath: String,
        ) {
            reasons += reason
        }
    }
}

private object NotificationFlags {
    const val GROUP_SUMMARY = 512
    const val LOCAL_ONLY = 256
}
