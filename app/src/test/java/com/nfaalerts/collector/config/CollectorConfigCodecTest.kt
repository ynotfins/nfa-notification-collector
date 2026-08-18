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
            result.errors.contains(ConfigValidationError("/deviceId", "DEVICE_ID_FORMAT", "Device ID is invalid.")),
        )
        assertTrue(
            result.errors.contains(
                ConfigValidationError(
                    "/activeEndpointProfile",
                    "ACTIVE_ENDPOINT_MISSING",
                    "Active endpoint profile does not exist.",
                ),
            ),
        )
        assertTrue(result.errors.any { it.path == "/endpointProfiles/clear/baseUrl" && it.code == "HTTPS_REQUIRED" })
        assertTrue(
            result.errors.any {
                it.path == "/endpointProfiles/clear/ingestPath" &&
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
    fun exportPreservesUnknownSafeObjectFieldOrderAndBytes() {
        val payload =
            """{"configVersion":1,"sources":[],"futureSafe":{"z":1,"a":2}}""".encodeToByteArray()

        val result = codec.decode(payload) as ConfigDecodeResult.Valid
        val exported = codec.exportPayload(result.document).decodeToString()

        assertTrue(exported.contains("\"futureSafe\":{\"z\":1,\"a\":2}"))
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
                "accessTokenValue",
                "databasePassword",
                "clientSecretValue",
                "privateKeyPem",
                "apiKeyValue",
                "serviceCredential",
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
    fun unknownNestedSecretContainersAndBearerShapedValuesAreRejectedWithoutExport() {
        val bearerShape = "A".repeat(43)
        val prohibitedKeys =
            listOf(
                "credentials",
                "secrets",
                "authHeader",
                "password",
                "clientSecret",
                "privateKey",
                "accessTokenValue",
                "apiKey",
                "bearer",
                "authorization",
                "credential",
                "ciphertext",
            )

        prohibitedKeys.forEach { key ->
            val result =
                codec.decode(
                    """{"configVersion":1,"sources":[],"future":{"$key":"safe"}}""".encodeToByteArray(),
                ) as ConfigDecodeResult.Invalid
            assertTrue("key=$key", result.errors.any { it.code == "PROHIBITED_CONFIG_FIELD" })
        }

        val result =
            codec.decode(
                """{"configVersion":1,"sources":[],"future":{"nested":["$bearerShape"]}}""".encodeToByteArray(),
            ) as ConfigDecodeResult.Invalid

        assertTrue(result.errors.any { it.path == "/future/nested/0" && it.code == "PROHIBITED_CONFIG_VALUE" })
        assertFalse(
            codec.isExportable(
                """{"configVersion":1,"sources":[],"future":"$bearerShape"}""".encodeToByteArray(),
            ),
        )
    }

    @Test
    fun deliveryTimeoutAndBackoffBoundsAreValidatedAsOneConfiguration() {
        val invalid =
            """
            {
              "configVersion":1,
              "sources":[],
              "delivery": {
                "connectTimeoutMs":4999,
                "readTimeoutMs":120001,
                "initialBackoffMs":29999,
                "maxBackoffMs":29998
              }
            }
            """.trimIndent().encodeToByteArray()
        val invalidResult = codec.decode(invalid) as ConfigDecodeResult.Invalid

        listOf(
            "/delivery/connectTimeoutMs",
            "/delivery/readTimeoutMs",
            "/delivery/initialBackoffMs",
            "/delivery/maxBackoffMs",
        ).forEach { path ->
            assertTrue("missing $path", invalidResult.errors.any { it.path == path })
        }

        val boundary =
            """
            {
              "configVersion":1,
              "sources":[],
              "delivery": {
                "connectTimeoutMs":5000,
                "readTimeoutMs":120000,
                "initialBackoffMs":30000,
                "maxBackoffMs":21600000
              }
            }
            """.trimIndent().encodeToByteArray()

        assertTrue(codec.decode(boundary) is ConfigDecodeResult.Valid)
    }

    @Test
    fun endpointsAreRestrictedToHttpsOriginAndRelativeIngestPath() {
        listOf(
            "https://user:password@example.invalid",
            "https://example.invalid/not-an-origin",
            "https://example.invalid?query=value",
            "https://example.invalid#fragment",
            "https://example.invalid:0",
        ).forEach { baseUrl ->
            val result =
                codec.decode(
                    """
                    {"configVersion":1,"sources":[],"endpointProfiles":
                    {"tailscale":{"baseUrl":"$baseUrl","ingestPath":"/v1/ingest/alerts"}}}
                    """.trimIndent().encodeToByteArray(),
                ) as ConfigDecodeResult.Invalid
            assertTrue(
                "baseUrl=$baseUrl",
                result.errors.any { it.path == "/endpointProfiles/tailscale/baseUrl" },
            )
        }
        listOf(
            "//other.invalid/ingest",
            "/ingest?query=value",
            "/ingest#fragment",
            "https://other.invalid/ingest",
        ).forEach { path ->
            val result =
                codec.decode(
                    """
                    {"configVersion":1,"sources":[],"endpointProfiles":
                    {"tailscale":{"baseUrl":"https://example.invalid","ingestPath":"$path"}}}
                    """.trimIndent().encodeToByteArray(),
                ) as ConfigDecodeResult.Invalid
            assertTrue(
                "path=$path",
                result.errors.any { it.path == "/endpointProfiles/tailscale/ingestPath" },
            )
        }
    }

    @Test
    fun nonsecretWordsThatOnlyContainSensitiveSubstringsRemainPreserved() {
        val payload =
            """{"configVersion":1,"sources":[],"secretaryLabel":"dispatch","tokenizationMode":"none","privateMode":false}"""

        val result = codec.decode(payload.encodeToByteArray()) as ConfigDecodeResult.Valid
        val exported = codec.exportPayload(result.document).decodeToString()

        assertTrue(exported.contains("\"secretaryLabel\":\"dispatch\""))
        assertTrue(exported.contains("\"tokenizationMode\":\"none\""))
        assertTrue(exported.contains("\"privateMode\":false"))
    }

    @Test
    fun wrongJsonShapesReturnTypedErrorsInsteadOfThrowing() {
        val payload =
            """{"configVersion":1,"deviceId":{},"activeEndpointProfile":[],"endpointProfiles":[],"sources":{},"delivery":"bad","retention":false,"diagnostics":0}"""

        val result = codec.decode(payload.encodeToByteArray()) as ConfigDecodeResult.Invalid

        listOf(
            "/deviceId",
            "/activeEndpointProfile",
            "/endpointProfiles",
            "/sources",
            "/delivery",
            "/retention",
            "/diagnostics",
        ).forEach { path -> assertTrue("missing $path", result.errors.any { it.path == path }) }
    }

    @Test
    fun everySourceItemIsFullyValidatedBeforeSave() {
        val payload = """{"configVersion":1,"sources":[{}]}""".encodeToByteArray()

        val result = codec.decode(payload) as ConfigDecodeResult.Invalid

        listOf(
            "/sources/0/packageName",
            "/sources/0/appLabel",
            "/sources/0/sourceId",
            "/sources/0/enabled",
            "/sources/0/bnnMappingConfirmed",
            "/sources/0/rawTextOrder",
        ).forEach { path -> assertTrue("missing $path", result.errors.any { it.path == path }) }
    }

    @Test
    fun sourceDuplicatesCandidateEnumsAndBnnConfirmationAreRejected() {
        val payload =
            """
            {
              "configVersion": 1,
              "sources": [
                {
                  "packageName": "com.example.same",
                  "appLabel": "One",
                  "sourceId": "bnn",
                  "enabled": true,
                  "bnnMappingConfirmed": false,
                  "rawTextOrder": ["bigText", "unknown", "bigText"]
                },
                {
                  "packageName": "com.example.same",
                  "appLabel": "Two",
                  "sourceId": "weather",
                  "enabled": true,
                  "bnnMappingConfirmed": true,
                  "rawTextOrder": ["text"]
                }
              ]
            }
            """.trimIndent().encodeToByteArray()

        val result = codec.decode(payload) as ConfigDecodeResult.Invalid

        assertTrue(result.errors.any { it.path == "/sources/1/packageName" && it.code == "DUPLICATE_SOURCE_PACKAGE" })
        assertTrue(
            result.errors.any {
                it.path == "/sources/0/bnnMappingConfirmed" &&
                    it.code == "BNN_CONFIRMATION_REQUIRED"
            },
        )
        assertTrue(
            result.errors.any {
                it.path == "/sources/1/bnnMappingConfirmed" &&
                    it.code == "BNN_CONFIRMATION_FORBIDDEN"
            },
        )
        assertTrue(result.errors.any { it.path == "/sources/0/rawTextOrder/1" && it.code == "RAW_TEXT_FIELD_UNKNOWN" })
        assertTrue(
            result.errors.any { it.path == "/sources/0/rawTextOrder/2" && it.code == "RAW_TEXT_FIELD_DUPLICATE" },
        )
    }

    @Test
    fun jsonPointersEscapeSlashAndTildeSegments() {
        val payload =
            """{"configVersion":1,"activeEndpointProfile":"a/b~c","endpointProfiles":{"a/b~c":{"baseUrl":"http://invalid","ingestPath":"bad"}},"sources":[]}"""

        val result = codec.decode(payload.encodeToByteArray()) as ConfigDecodeResult.Invalid

        assertTrue(result.errors.any { it.path == "/endpointProfiles/a~1b~0c/baseUrl" })
        assertTrue(result.errors.any { it.path == "/endpointProfiles/a~1b~0c/ingestPath" })
    }

    @Test
    fun payloadOverOneMibFailsBeforeJsonParse() {
        val result = codec.decode(ByteArray(1_048_577) { '{'.code.toByte() }) as ConfigDecodeResult.Invalid

        assertEquals(
            ConfigValidationError("/", "CONFIG_PAYLOAD_LIMIT", "Configuration exceeds 1 MiB."),
            result.errors.single(),
        )
    }
}
