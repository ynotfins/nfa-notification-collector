package com.nfaalerts.collector.delivery

import com.nfaalerts.collector.data.CapturedNotificationEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WireProjectorTest {
    private val projector = WireProjector()

    @Test
    fun projectionIsCanonicalAndPreservesRawTextExactly() {
        val raw = "  FIRE 🚒\n123 Main St.\r\nDo NOT normalize—É  "
        val capture = capture(rawText = raw)

        val first = projector.project(capture) as WireProjectionResult.Ready
        val second = projector.project(capture) as WireProjectionResult.Ready
        val body = Json.parseToJsonElement(first.bodyBytes.decodeToString()).jsonObject

        assertArrayEquals(first.bodyBytes, second.bodyBytes)
        assertEquals(raw, body.getValue("rawText").jsonPrimitive.content)
        assertEquals(JsonPrimitive("bnn"), body["source"])
        assertEquals(JsonPrimitive(1), body["schemaVersion"])
        assertEquals(JsonPrimitive("nfa-primary-phone"), body["deviceId"])
    }

    @Test
    fun metadataAlwaysIncludesMandatoryProvenanceAndExplicitProjectionMarkers() {
        val hugeEnvelope =
            JsonObject(
                (0 until 200).associate { index ->
                    "key-${index.toString().padStart(3, '0')}" to JsonPrimitive("é".repeat(5_000))
                },
            ).toString()
        val result = projector.project(capture(envelopeJson = hugeEnvelope)) as WireProjectionResult.Ready
        val metadata = result.metadata
        val projection = metadata.getValue("projection").jsonObject
        val metrics = measure(metadata)

        listOf(
            "clientEventId",
            "configuredSource",
            "localEnvelopeSha256",
            "localEnvelopeUtf8Bytes",
            "notificationIdentity",
            "packageName",
            "projectionVersion",
            "rawCandidateProvenance",
        ).forEach { assertTrue("missing $it", it in metadata) }
        assertTrue(projection.getValue("omittedCount").toString().toInt() > 0)
        assertTrue(metrics.utf8Bytes <= 32_768)
        assertTrue(metrics.depth <= 6)
        assertTrue(metrics.totalKeys <= 128)
        assertTrue(metrics.maxObjectKeys <= 64)
        assertTrue(metrics.maxArrayEntries <= 128)
        assertTrue(metrics.maxStringUtf8Bytes <= 8_192)
        assertTrue(result.bodyBytes.size <= 262_144)
    }

    @Test
    fun rawTextLimitAndNulQuarantineWithoutTruncation() {
        val oversized = projector.project(capture(rawText = "x".repeat(131_073)))
        val nul = projector.project(capture(rawText = "not\u0000wire-safe"))

        assertEquals(
            WireProjectionResult.Quarantined("WIRE_RAW_TEXT_LIMIT", 131_073),
            oversized,
        )
        assertEquals(WireProjectionResult.Quarantined("WIRE_RAW_TEXT_NUL", 1), nul)
    }

    @Test
    fun nonBnnIsBlockedBeforeTransport() {
        assertEquals(WireProjectionResult.BlockedContract, projector.project(capture(sourceId = "weather")))
    }

    @Test
    fun configuredDeviceIdAndStablePriorityPrecedeLexicographicOptionalPaths() {
        val envelope =
            JsonObject(
                mapOf(
                    "aLowPriority" to JsonObject((0 until 80).associate { "k$it" to JsonPrimitive("low") }),
                    "notification" to JsonObject(mapOf("title" to JsonPrimitive("priority"))),
                ),
            ).toString()

        val result =
            projector.project(
                capture(envelopeJson = envelope),
                deviceId = "configured-phone",
            ) as WireProjectionResult.Ready
        val body = Json.parseToJsonElement(result.bodyBytes.decodeToString()).jsonObject
        val optional =
            result.metadata
                .getValue("projection")
                .jsonObject
                .getValue("optional")
                .jsonObject

        assertEquals("configured-phone", body.getValue("deviceId").jsonPrimitive.content)
        assertTrue("$.localEnvelope.notification.title" in optional)
    }

    private fun capture(
        sourceId: String = "bnn",
        rawText: String = "raw",
        envelopeJson: String = """{"notification":{"title":"Alert"}}""",
    ) = CapturedNotificationEntity(
        eventId = "123e4567-e89b-12d3-a456-426614174000",
        packageName = "us.example.bnn",
        sourceId = sourceId,
        notificationKey = "notification-key",
        notificationId = 42,
        notificationTag = "tag",
        postTimeEpochMillis = 1_755_516_000_000L,
        capturedAtEpochMillis = 1_755_516_100_000L,
        rawText = rawText,
        rawCandidatesJson = """{"bigText":["raw"],"text":["short"]}""",
        envelopeJson = envelopeJson,
        envelopeSha256 = "abc123",
        envelopeUtf8Bytes = envelopeJson.encodeToByteArray().size,
    )

    private data class Metrics(
        val utf8Bytes: Int,
        val depth: Int,
        val totalKeys: Int,
        val maxObjectKeys: Int,
        val maxArrayEntries: Int,
        val maxStringUtf8Bytes: Int,
    )

    private fun measure(root: JsonObject): Metrics {
        var keys = 0
        var maxObject = 0
        var maxArray = 0
        var maxString = 0

        fun walk(
            element: JsonElement,
            depth: Int,
        ): Int =
            when (element) {
                is JsonObject -> {
                    keys += element.size
                    maxObject = maxOf(maxObject, element.size)
                    maxOf(depth, element.values.maxOfOrNull { walk(it, depth + 1) } ?: depth)
                }

                is JsonArray -> {
                    maxArray = maxOf(maxArray, element.size)
                    maxOf(depth, element.maxOfOrNull { walk(it, depth + 1) } ?: depth)
                }

                is JsonPrimitive -> {
                    if (element.isString) maxString = maxOf(maxString, element.content.encodeToByteArray().size)
                    depth
                }
            }
        val depth = walk(root, 1)
        return Metrics(root.toString().encodeToByteArray().size, depth, keys, maxObject, maxArray, maxString)
    }
}
