package com.nfaalerts.collector.diagnostics

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.charset.StandardCharsets

class DiagnosticSanitizer {
    fun safeDetails(values: Map<String, Any?>): String {
        val safe = linkedMapOf<String, kotlinx.serialization.json.JsonElement>()
        values.toSortedMap().forEach { (key, value) ->
            if (key !in ALLOWED_FIELDS || safe.size >= MAX_FIELDS) return@forEach
            val primitive =
                when (value) {
                    null -> JsonPrimitive("missing")
                    is Boolean -> JsonPrimitive(value)
                    is Number -> JsonPrimitive(value)
                    else -> JsonPrimitive(value.toString().take(MAX_VALUE_CHARS))
                }
            safe[key] = primitive
        }
        var output = JsonObject(safe).toString()
        while (output.toByteArray(StandardCharsets.UTF_8).size > MAX_BYTES && safe.isNotEmpty()) {
            safe.remove(safe.keys.last())
            output = JsonObject(safe).toString()
        }
        return output
    }

    companion object {
        private const val MAX_BYTES = 4_096
        private const val MAX_FIELDS = 24
        private const val MAX_VALUE_CHARS = 512
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
    }
}
