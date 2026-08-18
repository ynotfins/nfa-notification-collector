package com.nfaalerts.collector.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class CollectorReadinessTest {
    @Test
    fun `ready requires every required setup condition`() {
        val base =
            CollectorReadiness(
                notificationAccessGranted = true,
                endpointIsValid = true,
                bearerSaved = true,
                deviceIdIsValid = true,
                enabledSourceCount = 1,
            )

        assertEquals(CollectorReadinessState.Ready, base.state)
        assertEquals(CollectorReadinessState.SetupRequired, base.copy(bearerSaved = false).state)
        assertEquals(CollectorReadinessState.SetupRequired, base.copy(enabledSourceCount = 0).state)
        assertEquals(CollectorReadinessState.SetupRequired, base.copy(endpointIsValid = false).state)
    }
}
