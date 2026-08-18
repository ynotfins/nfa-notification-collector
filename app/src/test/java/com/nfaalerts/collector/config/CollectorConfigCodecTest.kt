package com.nfaalerts.collector.config

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CollectorConfigCodecTest {
    private val codec = CollectorConfigCodec()

    @Test
    fun defaultsUseOneHttpsTailscaleProfileAndDurableLimits() {
        val document = codec.defaultDocument()

        assertEquals("nfa-primary-phone", document.config.deviceId)
        assertEquals("tailscale", document.config.activeEndpointProfile)
        assertEquals(
            "https://chaoscentral.tailb71e7e.ts.net",
            document.config.activeEndpoint.baseUrl,
        )
        assertEquals("/v1/ingest/alerts", document.config.activeEndpoint.ingestPath)
        assertEquals(30_000L, document.config.delivery.initialBackoffMs)
        assertEquals(21_600_000L, document.config.delivery.maxBackoffMs)
        assertEquals(90, document.config.retention.sentDays)
        assertEquals(10_000, document.config.retention.maxSentRows)
        assertEquals(14, document.config.diagnostics.retentionDays)
        assertEquals(2_000, document.config.diagnostics.maxRows)
        assertFalse(document.root.toString().contains("maxAttempts"))
    }

    @Test
    fun v1ValidationReturnsTypedJsonPathErrors() {
        val payload =
            """{"configVersion":1,"deviceId":"bad id","activeEndpointProfile":"missing","endpointProfiles":{"clear":{"baseUrl":"http://127.0.0.1:8787","ingestPath":"alerts"}},"sources":[]}"""

        val result = codec.decode(payload.encodeToByteArray()) as ConfigDecodeResult.Invalid

        assertTrue(
            result.errors.contains(ConfigValidationError("$.deviceId", "DEVICE_ID_FORMAT", "Device ID is invalid.")),
        )
        assertTrue(
            result.errors.contains(
                ConfigValidationError(
                    "$.activeEndpointProfile",
                    "ACTIVE_ENDPOINT_MISSING",
                    "Active endpoint profile does not exist.",
                ),
            ),
        )
        assertTrue(result.errors.any { it.path == "$.endpointProfiles.clear.baseUrl" && it.code == "HTTPS_REQUIRED" })
        assertTrue(
            result.errors.any {
                it.path == "$.endpointProfiles.clear.ingestPath" &&
                    it.code == "INGEST_PATH_FORMAT"
            },
        )
    }

    @Test
    fun validV1NormalizesKnownFieldsAndPreservesUnknownSafeFields() {
        val payload =
            """{"configVersion":1,"sources":[],"futureSafe":{"nested":[1,true,null]},"endpointProfiles":{"tailscale":{"baseUrl":"https://example.invalid","ingestPath":"/v1/ingest/alerts","futureTls":"strict"}},"activeEndpointProfile":"tailscale"}"""

        val result = codec.decode(payload.encodeToByteArray()) as ConfigDecodeResult.Valid
        val reparsed = Json.parseToJsonElement(codec.exportPayload(result.document).decodeToString()).jsonObject

        assertEquals(Json.parseToJsonElement("""{"nested":[1,true,null]}"""), reparsed["futureSafe"])
        assertEquals(
            "\"strict\"",
            reparsed
                .getValue("endpointProfiles")
                .jsonObject
                .getValue("tailscale")
                .jsonObject
                .getValue("futureTls")
                .toString(),
        )
        assertEquals("nfa-primary-phone", result.document.config.deviceId)
    }

    @Test
    fun deterministicV0ImportProducesCanonicalV1() {
        val fixture =
            """{"version":0,"deviceId":"legacy-phone","serverUrl":"https://legacy.example","ingestPath":"/v1/ingest/alerts","sources":[],"legacyNote":"keep"}"""

        val first = codec.decode(fixture.encodeToByteArray()) as ConfigDecodeResult.Valid
        val second = codec.decode(fixture.encodeToByteArray()) as ConfigDecodeResult.Valid
        val firstBytes = codec.exportPayload(first.document)

        assertArrayEquals(firstBytes, codec.exportPayload(second.document))
        assertTrue(first.document.migratedFromVersion == 0)
        assertEquals("legacy", first.document.config.activeEndpointProfile)
        assertEquals("https://legacy.example", first.document.config.activeEndpoint.baseUrl)
        assertTrue(firstBytes.decodeToString().contains("\"legacyNote\":\"keep\""))
        assertFalse(firstBytes.decodeToString().contains("\"version\":0"))
    }

    @Test
    fun unknownFutureVersionFailsClosedAndRetainsOriginalPayload() {
        val payload = " { \"configVersion\" : 99, \"future\" : [3,2,1] } ".encodeToByteArray()

        val result = codec.decode(payload) as ConfigDecodeResult.Invalid

        assertEquals("FUTURE_VERSION_UNSUPPORTED", result.errors.single().code)
        assertArrayEquals(payload, result.originalPayload)
    }

    @Test
    fun importAndExportRejectSecretAndPrivatePayloadFields() {
        val sensitiveFields =
            listOf(
                "bearer",
                "authBearer",
                "authorization",
                "authorizationHeader",
                "ciphertext",
                "iv",
                "notificationPayload",
                "deliveryRows",
                "apiToken",
            )

        sensitiveFields.forEach { field ->
            val payload =
                """{"configVersion":1,"sources":[],"$field":"must-not-leave-device"}"""
                    .encodeToByteArray()
            val result = codec.importPayload(payload) as ConfigDecodeResult.Invalid

            assertTrue("field=$field", result.errors.any { it.code == "PROHIBITED_CONFIG_FIELD" })
            assertFalse(codec.isExportable(payload))
        }
    }

    @Test
    fun wrongJsonShapesReturnTypedErrorsInsteadOfThrowing() {
        val payload =
            """{"configVersion":1,"deviceId":{},"activeEndpointProfile":[],"endpointProfiles":[],"sources":{},"delivery":"bad","retention":false,"diagnostics":0}"""

        val result = codec.decode(payload.encodeToByteArray()) as ConfigDecodeResult.Invalid

        listOf(
            "$.deviceId",
            "$.activeEndpointProfile",
            "$.endpointProfiles",
            "$.sources",
            "$.delivery",
            "$.retention",
            "$.diagnostics",
        ).forEach { path -> assertTrue("missing $path", result.errors.any { it.path == path }) }
    }
}
