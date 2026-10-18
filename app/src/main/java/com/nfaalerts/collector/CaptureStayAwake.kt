package com.nfaalerts.collector

/**
 * Holds the CPU awake while the reliability foreground service is running.
 * A partial lock does not turn the screen on. Release it when that service stops.
 */
internal class CaptureStayAwake(
    private val lock: StayAwakeLock,
) {
    fun holdForCapture() {
        if (!lock.isHeld()) {
            lock.acquire()
        }
    }

    fun release() {
        if (lock.isHeld()) {
            lock.release()
        }
    }
}

internal interface StayAwakeLock {
    fun isHeld(): Boolean

    fun acquire()

    fun release()
}
