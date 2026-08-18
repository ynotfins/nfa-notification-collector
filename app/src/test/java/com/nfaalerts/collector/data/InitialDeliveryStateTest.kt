package com.nfaalerts.collector.data

import org.junit.Assert.assertEquals
import org.junit.Test

class InitialDeliveryStateTest {
    @Test
    fun `BNN capture starts pending`() {
        assertEquals(DeliveryState.PENDING, InitialDeliveryState.forSource("bnn"))
    }

    @Test
    fun `every non BNN source is contract blocked`() {
        listOf("other", "weather", "BNN", "").forEach { source ->
            assertEquals(DeliveryState.BLOCKED_CONTRACT, InitialDeliveryState.forSource(source))
        }
    }
}
