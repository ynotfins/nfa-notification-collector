package com.nfaalerts.collector.ui.settings

import com.nfaalerts.collector.config.CollectorConfigCodec
import com.nfaalerts.collector.config.ConfigDecodeResult
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsDraftTest {
    private val codec = CollectorConfigCodec()

    @Test
    fun `approved defaults reset every form value`() {
        val changed =
            SettingsDraft
                .from(codec.defaultDocument())
                .copy(deviceId = "changed", sentDays = "1", diagnosticsMaxRows = "1")

        val reset = changed.resetToApprovedDefaults()

        assertEquals("nfa-primary-phone", reset.deviceId)
        assertEquals("90", reset.sentDays)
        assertEquals("2000", reset.diagnosticsMaxRows)
        assertEquals("tailscale", reset.activeProfile)
    }

    @Test
    fun `profile selection and all bounded fields encode while unknown fields survive`() {
        val root =
            codec
                .defaultDocument()
                .root
                .toMutableMap()
                .apply { put("futureSafe", JsonPrimitive("preserve")) }
        val document =
            (
                codec.normalizeRoot(
                    kotlinx.serialization.json.JsonObject(root),
                ) as ConfigDecodeResult.Valid
            ).document
        val draft =
            SettingsDraft
                .from(document)
                .copy(
                    activeProfile = "backup",
                    profiles =
                        listOf(
                            EndpointProfileDraft("tailscale", "https://one.example", "/v1/ingest/alerts"),
                            EndpointProfileDraft("backup", "https://two.example", "/v1/ingest/alerts"),
                        ),
                    deviceId = "device-2",
                    connectTimeoutMs = "5000",
                    readTimeoutMs = "120000",
                    initialBackoffMs = "30000",
                    maxBackoffMs = "21600000",
                    sentDays = "30",
                    maxSentRows = "999",
                    diagnosticsDays = "7",
                    diagnosticsMaxRows = "500",
                )

        val decoded = draft.decode(codec) as ConfigDecodeResult.Valid

        assertEquals("backup", decoded.document.config.activeEndpointProfile)
        assertEquals("device-2", decoded.document.config.deviceId)
        assertEquals(
            "preserve",
            decoded.document.root
                .getValue("futureSafe")
                .toString()
                .trim('"'),
        )
    }

    @Test
    fun `invalid endpoint and bounds return exact RFC6901 paths`() {
        val draft =
            SettingsDraft
                .from(codec.defaultDocument())
                .copy(
                    profiles = listOf(EndpointProfileDraft("tailscale", "http://clear.example/path", "//bad")),
                    connectTimeoutMs = "1",
                    maxBackoffMs = "29999",
                    sentDays = "0",
                )

        val errors = (draft.decode(codec) as ConfigDecodeResult.Invalid).errors

        assertTrue(errors.any { it.path == "/endpointProfiles/tailscale/baseUrl" && it.code == "HTTPS_REQUIRED" })
        assertTrue(
            errors.any { it.path == "/endpointProfiles/tailscale/ingestPath" && it.code == "INGEST_PATH_FORMAT" },
        )
        assertTrue(errors.any { it.path == "/delivery/connectTimeoutMs" && it.code == "DELIVERY_RANGE" })
        assertTrue(errors.any { it.path == "/retention/sentDays" && it.code == "POSITIVE_INTEGER_REQUIRED" })
    }
}
