package com.nfaalerts.collector.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class GuidedSetupTest {
    @Test
    fun `next required step follows the operator setup order`() {
        val start = CollectorReadiness(false, false, false, false, 0)
        assertEquals(GuidedSetupStep.Access, GuidedSetup.next(start))
        assertEquals(
            GuidedSetupStep.Battery,
            GuidedSetup.next(start.copy(notificationAccessGranted = true)),
        )
        assertEquals(
            GuidedSetupStep.Endpoint,
            GuidedSetup.next(
                start.copy(notificationAccessGranted = true, endpointIsValid = false),
                batteryReviewed = true,
            ),
        )
    }
}
