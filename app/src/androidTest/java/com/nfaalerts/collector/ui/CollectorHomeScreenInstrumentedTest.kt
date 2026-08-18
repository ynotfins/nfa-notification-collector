package com.nfaalerts.collector.ui

import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.nfaalerts.collector.MainActivity
import com.nfaalerts.collector.config.ConfigValidationError
import com.nfaalerts.collector.config.InstalledApp
import com.nfaalerts.collector.config.SourceSelection
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class CollectorHomeScreenInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun setupAndPrimaryAreasAreReachableFromRestorableNavigation() {
        composeRule.activity.setContent {
            CollectorHomeScreen(
                repository = FakeCollectorUiRepository(),
                openNotificationAccessSettings = {},
                openBatterySettings = {},
            )
        }

        composeRule.onNodeWithText("Setup required").assertIsDisplayed()
        composeRule.onNodeWithText("Sources").performClick()
        composeRule.onNodeWithText("Sources (1 / 10)").assertIsDisplayed()
        composeRule.onNodeWithText("Delivery").performClick()
        composeRule.onNodeWithText("Recent delivery").assertIsDisplayed()
        composeRule.onNodeWithText("Settings").performClick()
        composeRule.onNodeWithText("Connection settings").assertIsDisplayed()
    }

    @Test
    fun tokenEntrySetsSecureFlagAndClearsItWhenDismissed() {
        composeRule.activity.setContent {
            CollectorHomeScreen(
                repository = FakeCollectorUiRepository(),
                openNotificationAccessSettings = {},
                openBatterySettings = {},
            )
        }

        composeRule.onNodeWithText("Settings").performClick()
        composeRule.onNodeWithText("Enter token").performClick()
        composeRule.runOnIdle {
            assertTrue(
                composeRule.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0,
            )
        }
        composeRule.onNodeWithText("Cancel").performClick()
        composeRule.runOnIdle {
            assertTrue(
                composeRule.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE == 0,
            )
        }
    }
}

private class FakeCollectorUiRepository : CollectorUiRepository {
    override suspend fun snapshot() =
        CollectorUiSnapshot(
            readiness =
                CollectorReadiness(
                    notificationAccessGranted = false,
                    endpointIsValid = true,
                    bearerSaved = false,
                    deviceIdIsValid = true,
                    enabledSourceCount = 1,
                ),
            endpoint = "https://example.invalid",
            deviceId = "synthetic-device",
            selectedCount = 1,
            queueCount = 0,
            listenerState = "Unknown",
        )

    override suspend fun installedApps(): List<InstalledApp> = emptyList()

    override suspend fun selectedSources(): List<SourceSelection> = emptyList()

    override suspend fun deliveryRows(): List<DeliveryUiRow> = emptyList()

    override suspend fun deliveryEnvelope(eventId: String): String? = null

    override suspend fun saveToken(value: CharArray): Boolean {
        value.fill('\u0000')
        return true
    }

    override suspend fun saveConfig(payload: String): List<ConfigValidationError> = emptyList()

    override suspend fun exportConfig(): ByteArray = ByteArray(0)

    override suspend fun importConfig(payload: ByteArray): List<ConfigValidationError> = emptyList()

    override suspend fun formattedConfig(): String = "{}"

    override suspend fun retry(eventId: String): Boolean = false
}
