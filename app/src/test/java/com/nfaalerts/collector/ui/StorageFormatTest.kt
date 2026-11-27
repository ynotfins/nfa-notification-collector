package com.nfaalerts.collector.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class StorageFormatTest {
    @Test
    fun formatsBytesWithBinaryUnits() {
        assertEquals("512 B", formatStorageBytes(512))
        assertEquals("1.0 KB", formatStorageBytes(1024))
        assertEquals("1.5 MB", formatStorageBytes(1024L * 1024L * 3L / 2L))
        assertEquals("2.0 GB", formatStorageBytes(2L * 1024L * 1024L * 1024L))
    }
}
