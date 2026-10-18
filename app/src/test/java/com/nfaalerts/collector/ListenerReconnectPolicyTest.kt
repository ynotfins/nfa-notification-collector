package com.nfaalerts.collector

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ListenerReconnectPolicyTest {
    @Test
    fun plainRebindComesFirstAndComponentResetIsOccasional() {
        assertFalse(ListenerReconnectPolicy.shouldResetComponent(0))
        assertFalse(ListenerReconnectPolicy.shouldResetComponent(1))
        assertFalse(ListenerReconnectPolicy.shouldResetComponent(4))
        assertTrue(ListenerReconnectPolicy.shouldResetComponent(5))
        assertFalse(ListenerReconnectPolicy.shouldResetComponent(6))
        assertTrue(ListenerReconnectPolicy.shouldResetComponent(10))
    }
}
