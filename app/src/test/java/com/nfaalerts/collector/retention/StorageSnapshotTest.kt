package com.nfaalerts.collector.retention

import com.nfaalerts.collector.config.RetentionConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageSnapshotTest {
    private val retention =
        RetentionConfig(
            sentDays = 180,
            maxSentRows = 100_000,
            pressureMaxRows = 50_000,
            pressureMaxBytes = 200L * 1024L * 1024L,
        )

    @Test
    fun rowThresholdTriggersPressureRelease() {
        assertTrue(StorageSnapshot(capturedRows = 50_000, unsentRows = 0, databaseBytes = 1).exceedsPressure(retention))
    }

    @Test
    fun byteThresholdTriggersPressureRelease() {
        assertTrue(
            StorageSnapshot(
                capturedRows = 1,
                unsentRows = 1,
                databaseBytes = 200L * 1024L * 1024L,
            ).exceedsPressure(retention),
        )
    }

    @Test
    fun healthyStorageDoesNotTriggerPressureRelease() {
        assertFalse(
            StorageSnapshot(
                capturedRows = 49_999,
                unsentRows = 0,
                databaseBytes = 199L * 1024L * 1024L,
            ).exceedsPressure(retention),
        )
    }
}
