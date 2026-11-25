package com.nfaalerts.collector.config

import com.nfaalerts.collector.capture.RawTextField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultSourcesTest {
    @Test
    fun `bnn is restored only when it is installed and missing`() {
        assertNull(DefaultSources.missingBnn(emptyList(), bnnInstalled = false))
        assertNull(DefaultSources.missingBnn(listOf(DefaultSources.bnn()), bnnInstalled = true))

        val restored = DefaultSources.missingBnn(emptyList(), bnnInstalled = true)!!
        assertEquals("us.bnn.newsapp", restored.packageName)
        assertEquals("bnn", restored.sourceId)
        assertTrue(restored.enabled)
        assertTrue(restored.bnnMappingConfirmed)
        assertEquals(RawTextField.DEFAULT_ORDER, restored.rawTextOrder)
    }

    @Test
    fun `restoring bnn does not remove or change another source`() {
        val other =
            SourceSelection(
                packageName = "com.example.other",
                appLabel = "Other",
                sourceId = "other",
                enabled = true,
                bnnMappingConfirmed = false,
                rawTextOrder = listOf(RawTextField.TEXT),
            )

        val restored = DefaultSources.missingBnn(listOf(other), bnnInstalled = true)!!

        assertEquals("us.bnn.newsapp", restored.packageName)
        assertEquals("com.example.other", other.packageName)
        assertEquals(listOf(RawTextField.TEXT), other.rawTextOrder)
    }
}
