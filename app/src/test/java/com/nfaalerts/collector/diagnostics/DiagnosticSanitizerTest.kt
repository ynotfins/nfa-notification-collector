package com.nfaalerts.collector.diagnostics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticSanitizerTest {
    @Test
    fun diagnosticsDropSecretsHeadersBodiesAndBoundOutput() {
        val output =
            DiagnosticSanitizer().safeDetails(
                mapOf(
                    "eventId" to "123e4567-e89b-12d3-a456-426614174000",
                    "state" to "RETRY_WAIT",
                    "attempt" to 8,
                    "authorization" to "must-never-appear",
                    "bearer" to "must-never-appear",
                    "rawText" to "private body",
                    "stackTrace" to "private stack",
                    "longValue" to "x".repeat(10_000),
                ),
            )

        assertTrue(output.encodeToByteArray().size <= 4_096)
        assertTrue(output.contains("eventId"))
        assertTrue(output.contains("RETRY_WAIT"))
        listOf("must-never-appear", "private body", "private stack", "authorization", "bearer", "rawText").forEach {
            assertFalse(it, output.contains(it, ignoreCase = true))
        }
    }
}
