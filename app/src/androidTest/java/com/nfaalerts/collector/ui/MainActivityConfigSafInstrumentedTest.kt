package com.nfaalerts.collector.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import com.nfaalerts.collector.MainActivity
import com.nfaalerts.collector.config.CollectorConfigCodec
import com.nfaalerts.collector.config.CollectorConfigRepository
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class MainActivityConfigSafInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun productionSafImportReloadsVisibleSettingsAndSourcesAndReportsAllTransferOutcomes() {
        val defaults = CollectorConfigCodec().exportPayload(CollectorConfigCodec().defaultDocument())
        try {
            composeRule.activity.importConfiguration(ConfigTestProvider.seed("defaults", defaults, context))
            waitForPersistedDevice("nfa-primary-phone")
            composeRule.onNodeWithText("Settings").performClick()

            val imported = importedPayload()
            composeRule.activity.importConfiguration(ConfigTestProvider.seed("imported", imported, context))
            waitForPersistedDevice("imported-device")
            settingsScrollTo(hasTestTag("device-id-form"))
            composeRule.onNodeWithTag("device-id-form").assertTextContains("imported-device")
            settingsScrollTo(hasTestTag("json-config-editor"))
            val jsonText =
                composeRule
                    .onNodeWithTag("json-config-editor")
                    .fetchSemanticsNode()
                    .config[SemanticsProperties.EditableText]
                    .text
            assertTrue(jsonText.contains("futureSafe"))

            settingsScrollTo(hasTestTag("device-id-form"))
            composeRule.onNodeWithTag("device-id-form").performTextReplacement("form-device")
            settingsScrollTo(hasText("Save settings forms"))
            composeRule.onNodeWithText("Save settings forms").performClick()
            waitForPersistedDevice("form-device")
            val afterFormSave = runBlocking { CollectorConfigRepository(context).exportPayload() }
            val afterRoot = Json.parseToJsonElement(afterFormSave.decodeToString()).jsonObject
            assertEquals(
                "preserved",
                afterRoot
                    .getValue("futureSafe")
                    .jsonObject
                    .getValue("marker")
                    .jsonPrimitive.content,
            )

            composeRule.onNodeWithText("Sources").performClick()
            composeRule.onNodeWithText("Sources (1 / 10)").assertIsDisplayed()
            composeRule.onNodeWithText("Imported Source", substring = true).assertIsDisplayed()
            composeRule.onNodeWithText("Settings").performClick()

            val oversize = ByteArray(CollectorConfigCodec.MAX_PAYLOAD_BYTES + 1) { 'x'.code.toByte() }
            composeRule.activity.importConfiguration(ConfigTestProvider.seed("oversize", oversize, context))
            settingsScrollTo(hasText("/: CONFIG_PAYLOAD_LIMIT — Configuration exceeds 1 MiB."))
            composeRule.onNodeWithText("/: CONFIG_PAYLOAD_LIMIT — Configuration exceeds 1 MiB.").assertIsDisplayed()

            composeRule.activity.importConfiguration(
                ConfigTestProvider.seed("invalid", "not-json".encodeToByteArray(), context),
            )
            settingsScrollTo(hasText("/: INVALID_JSON — Configuration is not valid JSON."))
            composeRule.onNodeWithText("/: INVALID_JSON — Configuration is not valid JSON.").assertIsDisplayed()

            composeRule.activity.exportConfiguration(ConfigTestProvider.uri("export-success"))
            settingsScrollTo(hasText("Configuration export completed."))
            composeRule.onNodeWithText("Configuration export completed.").assertIsDisplayed()
            assertTrue(ConfigTestProvider.output("export-success").decodeToString().contains("futureSafe"))

            composeRule.activity.exportConfiguration(ConfigTestProvider.failWrite("export-failure"))
            settingsScrollTo(hasText("Configuration export failed."))
            composeRule.onNodeWithText("Configuration export failed.").assertIsDisplayed()
        } finally {
            runBlocking {
                CollectorConfigRepository(context).savePayload(defaults)
                (composeRule.activity.application as com.nfaalerts.collector.NfaCollectorApp)
                    .appContainer.sourceSelections
                    .load()
            }
            defaults.fill(0)
            ConfigTestProvider.clear()
        }
    }

    private fun settingsScrollTo(matcher: androidx.compose.ui.test.SemanticsMatcher) {
        composeRule.onNodeWithTag("settings-list").performScrollToNode(matcher)
    }

    private fun waitForPersistedDevice(deviceId: String) {
        composeRule.waitUntil(10_000) {
            runBlocking {
                runCatching {
                    val payload = CollectorConfigRepository(context).exportPayload()
                    Json
                        .parseToJsonElement(payload.decodeToString())
                        .jsonObject
                        .getValue("deviceId")
                        .jsonPrimitive.content == deviceId
                }.getOrDefault(false)
            }
        }
    }

    private fun importedPayload(): ByteArray =
        """
        {
          "configVersion": 1,
          "deviceId": "imported-device",
          "activeEndpointProfile": "tailscale",
          "endpointProfiles": {
            "tailscale": {
              "baseUrl": "https://imported.example",
              "ingestPath": "/v1/ingest/alerts",
              "futureTls": "preserve"
            }
          },
          "sources": [{
            "packageName": "com.example.imported",
            "appLabel": "Imported Source",
            "sourceId": "other",
            "enabled": true,
            "bnnMappingConfirmed": false,
            "rawTextOrder": ["ticker", "text"],
            "futureSource": true
          }],
          "delivery": {
            "connectTimeoutMs": 15000,
            "readTimeoutMs": 30000,
            "initialBackoffMs": 30000,
            "maxBackoffMs": 21600000
          },
          "retention": {"sentDays": 90, "maxSentRows": 10000},
          "diagnostics": {"retentionDays": 14, "maxRows": 2000},
          "futureSafe": {"marker": "preserved"}
        }
        """.trimIndent().encodeToByteArray()
}
