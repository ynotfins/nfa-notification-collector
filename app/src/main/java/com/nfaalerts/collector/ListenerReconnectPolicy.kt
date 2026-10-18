package com.nfaalerts.collector

/**
 * Android accepts a reconnect request and may ignore it. Keep asking.
 * After several ignored requests, reset the listener component once, then keep asking.
 * Never reset on every tick. That would flap the binding.
 */
internal object ListenerReconnectPolicy {
    const val TICKS_BEFORE_COMPONENT_RESET = 5

    fun shouldResetComponent(consecutiveDisconnectedTicks: Int): Boolean =
        consecutiveDisconnectedTicks > 0 &&
            consecutiveDisconnectedTicks % TICKS_BEFORE_COMPONENT_RESET == 0
}
