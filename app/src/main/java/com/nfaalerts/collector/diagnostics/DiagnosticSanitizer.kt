package com.nfaalerts.collector.diagnostics

import com.nfaalerts.collector.data.DiagnosticEventEntity
import com.nfaalerts.collector.data.DiagnosticsDao
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.TimeUnit

sealed interface DiagnosticRecordResult {
    data object Recorded : DiagnosticRecordResult

    data class Rejected(
        val code: String,
    ) : DiagnosticRecordResult
}

interface DiagnosticStore {
    suspend fun insert(event: DiagnosticEventEntity)

    suspend fun prune(
        cutoffEpochMillis: Long,
        maxRows: Int,
    )
}

internal class RoomDiagnosticStore(
    private val dao: DiagnosticsDao,
) : DiagnosticStore {
    override suspend fun insert(event: DiagnosticEventEntity) = dao.insertFromRepository(event)

    override suspend fun prune(
        cutoffEpochMillis: Long,
        maxRows: Int,
    ) {
        dao.prune(cutoffEpochMillis, maxRows)
    }
}

class DiagnosticRepository(
    private val store: DiagnosticStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
    private val retentionDays: Int = 14,
    private val maxRows: Int = 2_000,
) {
    suspend fun record(
        eventCode: String,
        values: Map<String, Any?>,
    ): DiagnosticRecordResult {
        if (!EVENT_CODE.matches(eventCode) || eventCode !in ALLOWED_EVENT_CODES) {
            return DiagnosticRecordResult.Rejected("EVENT_CODE_NOT_ALLOWED")
        }
        if (values.keys.any(::isSensitiveKey)) {
            return DiagnosticRecordResult.Rejected("SENSITIVE_DIAGNOSTIC_KEY")
        }
        if (values.keys.any { it !in ALLOWED_FIELDS }) {
            return DiagnosticRecordResult.Rejected("DIAGNOSTIC_KEY_NOT_ALLOWED")
        }
        if (values.values.filterIsInstance<String>().any(::isSensitiveValue)) {
            return DiagnosticRecordResult.Rejected("SENSITIVE_DIAGNOSTIC_VALUE")
        }
        if (values.values.any { it != null && it !is String && it !is Number && it !is Boolean }) {
            return DiagnosticRecordResult.Rejected("SCALAR_VALUE_REQUIRED")
        }
        if (values.any { (key, value) -> !hasSafeFieldFormat(key, value) }) {
            return DiagnosticRecordResult.Rejected("SENSITIVE_DIAGNOSTIC_VALUE")
        }
        val details = safeObject(values) ?: return DiagnosticRecordResult.Rejected("DIAGNOSTIC_ROW_LIMIT")
        val now = clock()
        store.insert(
            DiagnosticEventEntity(
                diagnosticId = idFactory(),
                createdAtEpochMillis = now,
                eventCode = eventCode,
                safeDetailsJson = details,
            ),
        )
        val cutoff = now - TimeUnit.DAYS.toMillis(retentionDays.toLong())
        store.prune(cutoff, maxRows)
        return DiagnosticRecordResult.Recorded
    }

    private fun safeObject(values: Map<String, Any?>): String? {
        val safe = linkedMapOf<String, JsonElement>()
        values.toSortedMap().forEach { (key, value) ->
            val primitive =
                when (value) {
                    null -> JsonPrimitive("missing")
                    is Boolean -> JsonPrimitive(value)
                    is Number -> JsonPrimitive(value)
                    else -> JsonPrimitive(value.toString().take(MAX_VALUE_CHARS))
                }
            safe[key] = primitive
        }
        val output = JsonObject(safe).toString()
        return output.takeIf { it.toByteArray(StandardCharsets.UTF_8).size <= MAX_BYTES }
    }

    private fun isSensitiveKey(key: String): Boolean {
        val normalized = key.lowercase().filter(Char::isLetterOrDigit)
        return SENSITIVE_FRAGMENTS.any(normalized::contains)
    }

    private fun isSensitiveValue(value: String): Boolean =
        value.startsWith("Bearer ", ignoreCase = true) || BEARER_TOKEN_SHAPE.matches(value)

    private fun hasSafeFieldFormat(
        key: String,
        value: Any?,
    ): Boolean =
        when (key) {
            "eventId" -> value is String && SAFE_IDENTIFIER.matches(value)
            "ingestId" -> value is String && runCatching { UUID.fromString(value) }.isSuccess
            "errorCode", "state", "listener" -> value is String && SAFE_UPPER_TOKEN.matches(value)
            "packageName" -> value is String && PACKAGE_NAME.matches(value)
            "source" -> value is String && SAFE_LOWER_TOKEN.matches(value)
            "attempt", "createdAt", "httpStatus", "nextAttemptAt", "queue" -> value is Number
            else -> false
        }

    companion object {
        private const val MAX_BYTES = 4_096
        private const val MAX_VALUE_CHARS = 512
        private val EVENT_CODE = Regex("[A-Z][A-Z0-9_]{0,63}")
        private val SAFE_UPPER_TOKEN = Regex("[A-Z][A-Z0-9_]{0,63}")
        private val SAFE_LOWER_TOKEN = Regex("[a-z][a-z0-9_-]{0,63}")
        private val SAFE_IDENTIFIER = Regex("[A-Za-z0-9._:-]{1,128}")
        private val PACKAGE_NAME = Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+")
        private val BEARER_TOKEN_SHAPE = Regex("[A-Za-z0-9_-]{43}")
        private val ALLOWED_EVENT_CODES =
            setOf(
                "CAPTURE_PERSISTED",
                "DELIVERY_PAUSED_AUTH",
                "DELIVERY_QUARANTINED",
                "DELIVERY_RETRY",
                "DELIVERY_SENT",
                "LISTENER_ACCEPTED",
                "LISTENER_REJECTED",
                "STALE_SENDING_RECOVERED",
            )
        private val ALLOWED_FIELDS =
            setOf(
                "attempt",
                "createdAt",
                "errorCode",
                "eventId",
                "httpStatus",
                "ingestId",
                "listener",
                "nextAttemptAt",
                "packageName",
                "queue",
                "source",
                "state",
            )
        private val SENSITIVE_FRAGMENTS =
            setOf(
                "accesstoken",
                "apikey",
                "authorization",
                "bearer",
                "ciphertext",
                "credential",
                "password",
                "privatekey",
                "rawtext",
                "secret",
                "stacktrace",
                "token",
            )
    }
}

class DiagnosticSanitizer {
    fun safeDetails(values: Map<String, Any?>): String =
        JsonObject(
            values
                .filterKeys { it in setOf("attempt", "errorCode", "eventId", "httpStatus", "state") }
                .toSortedMap()
                .mapValues { (_, value) -> JsonPrimitive(value?.toString()?.take(512) ?: "missing") },
        ).toString().take(4_096)
}
