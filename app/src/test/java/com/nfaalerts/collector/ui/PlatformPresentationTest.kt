package com.nfaalerts.collector.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PlatformPresentationTest {
    @Test
    fun `notification access unknown remains unknown and not ready`() {
        val presentation = NotificationAccessState.Unknown.presentation()
        val snapshot =
            CollectorUiSnapshot(
                readiness = CollectorReadiness(false, true, true, true, 1),
                endpoint = "https://example.invalid",
                deviceId = "device-a",
                selectedCount = 1,
                queueCount = 0,
                listenerState = "Disconnected",
                notificationAccessState = NotificationAccessState.Unknown,
            )

        assertEquals(NotificationAccessState.Unknown, snapshot.notificationAccessState)
        assertEquals("Unknown", presentation.label)
        assertEquals("Notification access could not be checked safely.", presentation.safeExplanation)
        assertFalse(snapshot.readiness.notificationAccessGranted)
    }

    @Test
    fun `internet capability alone is not validated connectivity`() {
        assertEquals(
            ConnectivityState.Disconnected,
            connectivityState(hasActiveNetwork = true, hasValidatedCapability = false),
        )
        assertEquals(
            ConnectivityState.Connected,
            connectivityState(hasActiveNetwork = true, hasValidatedCapability = true),
        )
        assertEquals(
            ConnectivityState.Disconnected,
            connectivityState(hasActiveNetwork = false, hasValidatedCapability = false),
        )
    }
}
