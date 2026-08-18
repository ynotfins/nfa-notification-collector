package com.nfaalerts.collector.diagnostics

import com.nfaalerts.collector.data.DiagnosticEventEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticRepositoryTest {
    @Test
    fun sensitiveKeysAndUnknownEventCodesAreRejectedBeforeDaoWrite() =
        runBlocking {
            val store = RecordingDiagnosticStore()
            val repository = DiagnosticRepository(store, clock = { 1_000L }, idFactory = { "id" })

            val sensitive = repository.record("DELIVERY_RETRY", mapOf("accessTokenValue" to "sentinel"))
            val sensitiveValue = repository.record("DELIVERY_RETRY", mapOf("errorCode" to "Bearer sentinel"))
            val unknown = repository.record("ARBITRARY_EVENT", mapOf("state" to "PENDING"))

            assertEquals(DiagnosticRecordResult.Rejected("SENSITIVE_DIAGNOSTIC_KEY"), sensitive)
            assertEquals(DiagnosticRecordResult.Rejected("SENSITIVE_DIAGNOSTIC_VALUE"), sensitiveValue)
            assertEquals(DiagnosticRecordResult.Rejected("EVENT_CODE_NOT_ALLOWED"), unknown)
            assertTrue(store.rows.isEmpty())
        }

    @Test
    fun allowedRowIsScalarSanitizedByteBoundedAndPrunedImmediately() =
        runBlocking {
            val store = RecordingDiagnosticStore()
            val repository =
                DiagnosticRepository(
                    store,
                    clock = { 2_000L },
                    idFactory = { "diagnostic-id" },
                    retentionDays = 14,
                    maxRows = 2_000,
                )

            val result =
                repository.record(
                    "DELIVERY_RETRY",
                    mapOf(
                        "eventId" to "event",
                        "state" to "RETRY_WAIT",
                        "attempt" to 4,
                        "errorCode" to "x".repeat(10_000),
                    ),
                )

            assertEquals(DiagnosticRecordResult.Recorded, result)
            val row = store.rows.single()
            assertTrue(row.safeDetailsJson.encodeToByteArray().size <= 4_096)
            assertTrue(row.safeDetailsJson.contains("RETRY_WAIT"))
            assertFalse(row.safeDetailsJson.contains("sentinel"))
            assertEquals(2_000, store.lastMaxRows)
            assertTrue(store.lastCutoff < 2_000L)
        }

    @Test
    fun objectAndArrayValuesAreRejectedRatherThanStringified() =
        runBlocking {
            val store = RecordingDiagnosticStore()
            val repository = DiagnosticRepository(store, clock = { 1L }, idFactory = { "id" })

            val result = repository.record("DELIVERY_RETRY", mapOf("state" to listOf("private")))

            assertEquals(DiagnosticRecordResult.Rejected("SCALAR_VALUE_REQUIRED"), result)
            assertTrue(store.rows.isEmpty())
        }

    private class RecordingDiagnosticStore : DiagnosticStore {
        val rows = mutableListOf<DiagnosticEventEntity>()
        var lastCutoff = 0L
        var lastMaxRows = 0

        override suspend fun insert(event: DiagnosticEventEntity) {
            rows += event
        }

        override suspend fun prune(
            cutoffEpochMillis: Long,
            maxRows: Int,
        ) {
            lastCutoff = cutoffEpochMillis
            lastMaxRows = maxRows
        }
    }
}
