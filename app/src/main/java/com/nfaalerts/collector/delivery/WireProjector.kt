package com.nfaalerts.collector.delivery

import com.nfaalerts.collector.config.CollectorConfigCodec
import com.nfaalerts.collector.data.CapturedNotificationEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant

sealed interface WireProjectionResult {
    data class Ready(
        val source: String,
        val bodyBytes: ByteArray,
        val metadata: JsonObject,
    ) : WireProjectionResult

    data class Quarantined(
        val code: String,
        val measured: Int,
    ) : WireProjectionResult

    data object BlockedContract : WireProjectionResult
}

class WireProjector(
    private val defaultDeviceId: String = CollectorConfigCodec.DEFAULT_DEVICE_ID,
) {
    fun project(
        capture: CapturedNotificationEntity,
        deviceId: String = defaultDeviceId,
    ): WireProjectionResult {
        if (capture.sourceId != BNN_SOURCE) return WireProjectionResult.BlockedContract
        val rawText = capture.rawText.orEmpty()
        if ('\u0000' in rawText) return WireProjectionResult.Quarantined("WIRE_RAW_TEXT_NUL", 1)
        val rawBytes = rawText.toByteArray(StandardCharsets.UTF_8).size
        if (rawBytes > RAW_TEXT_LIMIT) {
            return WireProjectionResult.Quarantined("WIRE_RAW_TEXT_LIMIT", rawBytes)
        }
        val envelope =
            try {
                Json.parseToJsonElement(capture.envelopeJson)
            } catch (_: Exception) {
                return WireProjectionResult.Quarantined("WIRE_ENVELOPE_INVALID", capture.envelopeUtf8Bytes)
            }
        val metadata = buildMetadata(capture, envelope)
        val metadataBytes = metadata.toString().toByteArray(StandardCharsets.UTF_8).size
        if (metadataBytes > METADATA_LIMIT) {
            return WireProjectionResult.Quarantined("WIRE_METADATA_LIMIT", metadataBytes)
        }
        val body =
            canonicalize(
                JsonObject(
                    sortedMapOf(
                        "capturedAt" to JsonPrimitive(Instant.ofEpochMilli(capture.capturedAtEpochMillis).toString()),
                        "deviceId" to JsonPrimitive(deviceId),
                        "metadata" to metadata,
                        "rawText" to JsonPrimitive(rawText),
                        "schemaVersion" to JsonPrimitive(1),
                        "source" to JsonPrimitive(BNN_SOURCE),
                    ),
                ),
            ).toString().toByteArray(StandardCharsets.UTF_8)
        return if (body.size > BODY_LIMIT) {
            WireProjectionResult.Quarantined("WIRE_BODY_LIMIT", body.size)
        } else {
            WireProjectionResult.Ready(BNN_SOURCE, body, metadata)
        }
    }

    private fun buildMetadata(
        capture: CapturedNotificationEntity,
        envelope: JsonElement,
    ): JsonObject {
        val truncations = mutableListOf<Truncation>()
        val notificationIdentity =
            JsonObject(
                sortedMapOf(
                    "id" to JsonPrimitive(capture.notificationId),
                    "key" to boundedString(capture.notificationKey, "$.notificationIdentity.key", truncations),
                    "postTimeEpochMillis" to JsonPrimitive(capture.postTimeEpochMillis),
                    "tag" to
                        (
                            capture.notificationTag?.let {
                                boundedString(it, "$.notificationIdentity.tag", truncations)
                            } ?: JsonNull
                        ),
                ),
            )
        val rawProvenance = rawCandidateProvenance(capture.rawCandidatesJson)
        val flattened = mutableListOf<Pair<String, JsonPrimitive>>()
        flatten(envelope, "$.localEnvelope", flattened)
        val optional = linkedMapOf<String, JsonElement>()
        var omittedCount = 0
        val omittedPaths = mutableListOf<String>()
        flattened
            .sortedWith(compareBy<Pair<String, JsonPrimitive>> { projectionPriority(it.first) }.thenBy { it.first })
            .forEach { (path, primitive) ->
                if (optional.size >= MAX_OPTIONAL_KEYS) {
                    omittedCount++
                    if (omittedPaths.size < MAX_MARKERS) omittedPaths += boundedPath(path)
                    return@forEach
                }
                val value =
                    if (primitive.isString) {
                        boundedString(primitive.content, path, truncations)
                    } else {
                        primitive
                    }
                optional[boundedPath(path)] = value
            }

        fun metadata(): JsonObject {
            val shownTruncations = truncations.take(MAX_MARKERS)
            val markerOverflow =
                (truncations.size - shownTruncations.size).coerceAtLeast(0) +
                    (omittedCount - omittedPaths.size).coerceAtLeast(0)
            val projection =
                JsonObject(
                    sortedMapOf(
                        "markerOverflowCount" to JsonPrimitive(markerOverflow),
                        "omittedCount" to JsonPrimitive(omittedCount),
                        "omittedPaths" to JsonArray(omittedPaths.map(::JsonPrimitive)),
                        "optional" to JsonObject(optional.toSortedMap()),
                        "truncated" to
                            JsonArray(
                                shownTruncations.map {
                                    JsonObject(
                                        sortedMapOf(
                                            "originalUtf8Bytes" to JsonPrimitive(it.originalBytes),
                                            "path" to JsonPrimitive(boundedPath(it.path)),
                                            "retainedUtf8Bytes" to JsonPrimitive(it.retainedBytes),
                                        ),
                                    )
                                },
                            ),
                        "truncatedCount" to JsonPrimitive(truncations.size),
                    ),
                )
            return canonicalize(
                JsonObject(
                    sortedMapOf(
                        "clientEventId" to JsonPrimitive(capture.eventId),
                        "configuredSource" to JsonPrimitive(capture.sourceId),
                        "localEnvelopeSha256" to JsonPrimitive(capture.envelopeSha256),
                        "localEnvelopeUtf8Bytes" to JsonPrimitive(capture.envelopeUtf8Bytes),
                        "notificationIdentity" to notificationIdentity,
                        "packageName" to boundedString(capture.packageName, "$.packageName", truncations),
                        "projection" to projection,
                        "projectionVersion" to JsonPrimitive(1),
                        "rawCandidateProvenance" to rawProvenance,
                    ),
                ),
            ).jsonObject
        }

        var result = metadata()
        while (result.toString().toByteArray(StandardCharsets.UTF_8).size > METADATA_LIMIT && optional.isNotEmpty()) {
            val removed = optional.keys.last()
            optional.remove(removed)
            omittedCount++
            if (omittedPaths.size < MAX_MARKERS) omittedPaths += boundedPath(removed)
            result = metadata()
        }
        return result
    }

    private fun rawCandidateProvenance(rawCandidatesJson: String): JsonArray {
        val root =
            runCatching { Json.parseToJsonElement(rawCandidatesJson).jsonObject }.getOrNull()
                ?: return JsonArray(emptyList())
        return JsonArray(
            root.toSortedMap().entries.take(MAX_RAW_FIELDS).map { (field, values) ->
                val array = values as? JsonArray ?: JsonArray(emptyList())
                JsonObject(
                    sortedMapOf(
                        "field" to JsonPrimitive(truncateUtf8(field, MAX_STRING_BYTES).first),
                        "sha256" to JsonPrimitive(sha256(values.toString())),
                        "utf8Bytes" to JsonPrimitive(values.toString().toByteArray(StandardCharsets.UTF_8).size),
                        "valueCount" to JsonPrimitive(array.size),
                    ),
                )
            },
        )
    }

    private fun flatten(
        element: JsonElement,
        path: String,
        output: MutableList<Pair<String, JsonPrimitive>>,
    ) {
        when (element) {
            is JsonObject -> element.toSortedMap().forEach { (key, value) -> flatten(value, "$path.$key", output) }
            is JsonArray -> element.forEachIndexed { index, value -> flatten(value, "$path[$index]", output) }
            is JsonPrimitive -> output += path to element
            JsonNull -> output += path to JsonPrimitive("null")
        }
    }

    private fun boundedString(
        value: String,
        path: String,
        truncations: MutableList<Truncation>,
    ): JsonPrimitive {
        val (retained, originalBytes) = truncateUtf8(value, MAX_STRING_BYTES)
        val retainedBytes = retained.toByteArray(StandardCharsets.UTF_8).size
        if (retainedBytes < originalBytes) truncations += Truncation(path, originalBytes, retainedBytes)
        return JsonPrimitive(retained)
    }

    private fun boundedPath(path: String): String = truncateUtf8(path, MAX_STRING_BYTES).first

    private fun projectionPriority(path: String): Int =
        when {
            path.startsWith("$.localEnvelope.notification.") -> 0
            path.startsWith("$.localEnvelope.rawTextSelectedField") -> 1
            path.startsWith("$.localEnvelope.statusBarNotification.") -> 2
            path.startsWith("$.localEnvelope.application.") -> 3
            else -> 4
        }

    private fun truncateUtf8(
        value: String,
        maximumBytes: Int,
    ): Pair<String, Int> {
        val originalBytes = value.toByteArray(StandardCharsets.UTF_8).size
        if (originalBytes <= maximumBytes) return value to originalBytes
        val builder = StringBuilder()
        var used = 0
        var index = 0
        while (index < value.length) {
            val codePoint = value.codePointAt(index)
            val text = String(Character.toChars(codePoint))
            val bytes = text.toByteArray(StandardCharsets.UTF_8).size
            if (used + bytes > maximumBytes) break
            builder.append(text)
            used += bytes
            index += Character.charCount(codePoint)
        }
        return builder.toString() to originalBytes
    }

    private fun canonicalize(element: JsonElement): JsonElement =
        when (element) {
            is JsonObject -> JsonObject(element.toSortedMap().mapValues { canonicalize(it.value) })
            is JsonArray -> JsonArray(element.map(::canonicalize))
            else -> element
        }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8)).joinToString("") {
            "%02x".format(it)
        }

    private data class Truncation(
        val path: String,
        val originalBytes: Int,
        val retainedBytes: Int,
    )

    companion object {
        private const val BNN_SOURCE = "bnn"
        private const val RAW_TEXT_LIMIT = 131_072
        private const val METADATA_LIMIT = 32_768
        private const val BODY_LIMIT = 262_144
        private const val MAX_STRING_BYTES = 8_192
        private const val MAX_OPTIONAL_KEYS = 40
        private const val MAX_MARKERS = 8
        private const val MAX_RAW_FIELDS = 8
    }
}
