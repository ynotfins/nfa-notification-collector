package com.nfaalerts.collector.ui

import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.nfaalerts.collector.MainActivity
import com.nfaalerts.collector.config.ConfigValidationError
import com.nfaalerts.collector.config.InstalledApp
import com.nfaalerts.collector.config.SourceSelection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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

    @Test
    fun statusReflectsRepositoryChangesWithoutNavigation() {
        val repository = FakeCollectorUiRepository()
        composeRule.activity.setContent {
            CollectorHomeScreen(
                repository = repository,
                openNotificationAccessSettings = {},
                openBatterySettings = {},
            )
        }
        composeRule.onNodeWithText("Setup required").assertIsDisplayed()

        composeRule.runOnIdle {
            repository.mutableState.value =
                repository.mutableState.value.copy(
                    readiness = CollectorReadiness(true, true, true, true, 1),
                    verificationComplete = true,
                )
        }

        composeRule.onNodeWithText("Ready — required setup and local verification are complete").assertIsDisplayed()
    }

    @Test
    fun guidedStepsOpenTheirRealDestinationsAndDialogs() {
        var accessOpened = false
        val repository = FakeCollectorUiRepository()
        composeRule.activity.setContent {
            CollectorHomeScreen(
                repository = repository,
                openNotificationAccessSettings = { accessOpened = true },
                openBatterySettings = {},
            )
        }

        composeRule.onNodeWithText("Grant notification access").performClick()
        composeRule.runOnIdle { assertTrue(accessOpened) }

        repository.mutableState.value = repository.mutableState.value.withReadiness(true, false, false, true, 0)
        composeRule.onNodeWithText("Configure endpoint and device").performClick()
        composeRule.onNodeWithText("Connection settings").assertIsDisplayed()

        composeRule.onNodeWithText("Status").performClick()
        repository.mutableState.value = repository.mutableState.value.withReadiness(true, true, false, true, 0)
        composeRule.onNodeWithText("Enter token").performClick()
        composeRule.onNodeWithText("Secure token entry").assertIsDisplayed()
        composeRule.onNodeWithText("Cancel").performClick()

        composeRule.onNodeWithText("Status").performClick()
        repository.mutableState.value = repository.mutableState.value.withReadiness(true, true, true, true, 0)
        composeRule.onNodeWithText("Choose sources").performClick()
        composeRule.onNodeWithText("Sources (1 / 10)").assertIsDisplayed()
    }

    @Test
    fun verifyTransitionsToTextualGreenSemanticReadyState() {
        val repository =
            FakeCollectorUiRepository(
                initial =
                    defaultSnapshot().copy(
                        readiness = CollectorReadiness(true, true, true, true, 1),
                        networkState = "Connected",
                    ),
            )
        composeRule.activity.setContent {
            CollectorHomeScreen(
                repository = repository,
                openNotificationAccessSettings = {},
                openBatterySettings = {},
            )
        }

        composeRule.onNodeWithText("Run local verification").performClick()

        composeRule.onNodeWithText("Ready — required setup and local verification are complete").assertIsDisplayed()
        composeRule
            .onNodeWithTag("collector-ready-container")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Collector ready"))
            .assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Ready status icon").assertIsDisplayed()
        assertTrue(repository.verifyCalls == 1)
    }

    @Test
    fun unknownNotificationAccessIsNeverPresentedAsRequired() {
        val repository =
            FakeCollectorUiRepository(
                initial =
                    defaultSnapshot().copy(
                        readiness = CollectorReadiness(false, true, true, true, 1),
                        notificationAccessState = NotificationAccessState.Unknown,
                    ),
            )
        composeRule.activity.setContent {
            CollectorHomeScreen(
                repository = repository,
                openNotificationAccessSettings = {},
                openBatterySettings = {},
            )
        }

        composeRule.onNodeWithText("Notification access: Unknown").assertIsDisplayed()
        composeRule.onNodeWithText("Notification access could not be checked safely.").assertIsDisplayed()
    }
}

private class FakeCollectorUiRepository(
    initial: CollectorUiSnapshot = defaultSnapshot(),
) : CollectorUiRepository {
    val mutableState =
        MutableStateFlow(initial)
    override val state: StateFlow<CollectorUiSnapshot> = mutableState
    var verifyCalls = 0

    override fun refreshPlatformState() = Unit

    override suspend fun verify(): Boolean {
        verifyCalls += 1
        mutableState.value = mutableState.value.copy(verificationComplete = true)
        return true
    }

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

private fun defaultSnapshot() =
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

private fun CollectorUiSnapshot.withReadiness(
    access: Boolean,
    endpoint: Boolean,
    bearer: Boolean,
    deviceId: Boolean,
    sources: Int,
) = copy(
    readiness = CollectorReadiness(access, endpoint, bearer, deviceId, sources),
    verificationComplete = false,
)
