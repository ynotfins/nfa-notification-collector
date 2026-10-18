package com.nfaalerts.collector

import org.junit.Assert.assertEquals
import org.junit.Test

class CaptureStayAwakeTest {
    @Test
    fun holdKeepsTheCpuAwakeUntilTheServiceReleasesIt() {
        val lock = FakeStayAwakeLock()
        val stayAwake = CaptureStayAwake(lock)

        stayAwake.holdForCapture()
        stayAwake.holdForCapture()
        assertEquals(1, lock.acquireCount)
        assertEquals(true, lock.isHeld())

        stayAwake.release()
        stayAwake.release()
        assertEquals(1, lock.releaseCount)
        assertEquals(false, lock.isHeld())
    }

    private class FakeStayAwakeLock : StayAwakeLock {
        var acquireCount = 0
        var releaseCount = 0
        private var held = false

        override fun isHeld(): Boolean = held

        override fun acquire() {
            acquireCount += 1
            held = true
        }

        override fun release() {
            releaseCount += 1
            held = false
        }
    }
}
