package com.nfaalerts.collector.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class GuidedSetupTest {
    @Test
    fun `next required step follows the operator setup order`() {
        val start = CollectorReadiness(false, false, false, false, 0)
        assertEquals(GuidedSetupStep.Access, GuidedSetup.next(start))
        assertEquals(
            GuidedSetupStep.Endpoint,
            GuidedSetup.next(start.copy(notificationAccessGranted = true)),
        )
        assertEquals(
            GuidedSetupStep.Token,
            GuidedSetup.next(start.copy(notificationAccessGranted = true, endpointIsValid = true)),
        )
        assertEquals(
            GuidedSetupStep.Sources,
            GuidedSetup.next(start.copy(true, true, true, true, 0)),
        )
        assertEquals(
            GuidedSetupStep.Verify,
            GuidedSetup.next(start.copy(true, true, true, true, 1)),
        )
        assertEquals(
            GuidedSetupStep.Ready,
            GuidedSetup.next(start.copy(true, true, true, true, 1), verificationComplete = true),
        )
    }
}
