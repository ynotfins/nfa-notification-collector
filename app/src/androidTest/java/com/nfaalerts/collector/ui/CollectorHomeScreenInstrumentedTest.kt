package com.nfaalerts.collector.ui

import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
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

    @Test
    fun savedTokenWithPendingRequeueClosesSecretDialogAndShowsSafeWarning() {
        val repository = FakeCollectorUiRepository()
        repository.tokenOutcome = TokenSaveOutcome.SavedRequeueFailed
        composeRule.activity.setContent {
            CollectorHomeScreen(
                repository = repository,
                openNotificationAccessSettings = {},
                openBatterySettings = {},
            )
        }

        composeRule.onNodeWithText("Settings").performClick()
        composeRule.onNodeWithText("Enter token").performClick()
        composeRule.onNodeWithText("Bearer token").performTextInput("transient-secret")
        composeRule.onNodeWithText("Save token").performClick()

        composeRule.onNodeWithText("Secure token entry").assertDoesNotExist()
        composeRule
            .onNodeWithText("Token saved, but delivery requeue failed. Re-enter the token or restart the app to retry.")
            .assertIsDisplayed()
    }

    @Test
    fun settingsPreloadsFormsAndJsonWithValidationAndPlatformLinks() {
        var accessOpened = false
        var batteryOpened = false
        val repository = FakeCollectorUiRepository()
        composeRule.activity.setContent {
            CollectorHomeScreen(
                repository = repository,
                openNotificationAccessSettings = { accessOpened = true },
                openBatterySettings = { batteryOpened = true },
            )
        }

        composeRule.onNodeWithText("Settings").performClick()
        composeRule.onNodeWithText("Endpoint profiles").assertIsDisplayed()
        composeRule.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("device-id-form"))
        composeRule.onNodeWithTag("device-id-form").assertIsDisplayed()
        composeRule.onNodeWithTag("settings-list").performScrollToNode(hasText("Validate only"))
        composeRule.onNodeWithText("Validate only").performClick()
        composeRule.onNodeWithText("Configuration is valid.").assertIsDisplayed()
        composeRule.onNodeWithTag("settings-list").performScrollToNode(hasText("Open notification access settings"))
        composeRule.onNodeWithText("Open notification access settings").performClick()
        composeRule.onNodeWithTag("settings-list").performScrollToNode(hasText("Open battery settings"))
        composeRule.onNodeWithText("Open battery settings").performClick()
        composeRule.runOnIdle {
            assertTrue(accessOpened)
            assertTrue(batteryOpened)
        }
    }

    @Test
    fun resetUsesApprovedDefaultsInsteadOfReloadingCurrentJson() {
        val repository = FakeCollectorUiRepository()
        repository.formatted =
            """{"configVersion":1,"deviceId":"changed-device","activeEndpointProfile":"tailscale","endpointProfiles":{"tailscale":{"baseUrl":"https://example.invalid","ingestPath":"/v1/ingest/alerts"}},"sources":[],"delivery":{"connectTimeoutMs":15000,"readTimeoutMs":30000,"initialBackoffMs":30000,"maxBackoffMs":21600000},"retention":{"sentDays":90,"maxSentRows":10000},"diagnostics":{"retentionDays":14,"maxRows":2000}}"""
        composeRule.activity.setContent {
            CollectorHomeScreen(
                repository = repository,
                openNotificationAccessSettings = {},
                openBatterySettings = {},
            )
        }

        composeRule.onNodeWithText("Settings").performClick()
        composeRule.onNodeWithTag("settings-list").performScrollToNode(hasText("Reset to approved defaults"))
        composeRule.onNodeWithText("Reset to approved defaults").performClick()

        composeRule.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("device-id-form"))
        composeRule.onNodeWithTag("device-id-form").assertTextContains("nfa-primary-phone")
    }

    @Test
    fun settingsSurfacesImportAndExportResultFeedback() {
        val repository = FakeCollectorUiRepository()
        composeRule.activity.setContent {
            CollectorHomeScreen(
                repository = repository,
                openNotificationAccessSettings = {},
                openBatterySettings = {},
            )
        }
        composeRule.onNodeWithText("Settings").performClick()

        repository.feedback.value = "/: CONFIG_PAYLOAD_LIMIT — Configuration exceeds 1 MiB."
        composeRule
            .onNodeWithTag("settings-list")
            .performScrollToNode(hasText("/: CONFIG_PAYLOAD_LIMIT — Configuration exceeds 1 MiB."))
        composeRule.onNodeWithText("/: CONFIG_PAYLOAD_LIMIT — Configuration exceeds 1 MiB.").assertIsDisplayed()

        repository.feedback.value = "Configuration export failed."
        composeRule.onNodeWithTag("settings-list").performScrollToNode(hasText("Configuration export failed."))
        composeRule.onNodeWithText("Configuration export failed.").assertIsDisplayed()
    }
}

private class FakeCollectorUiRepository(
    initial: CollectorUiSnapshot = defaultSnapshot(),
) : CollectorUiRepository {
    val mutableState =
        MutableStateFlow(initial)
    override val state: StateFlow<CollectorUiSnapshot> = mutableState
    var verifyCalls = 0
    var tokenOutcome = TokenSaveOutcome.Saved
    var formatted =
        com.nfaalerts.collector.config
            .CollectorConfigCodec()
            .defaultDocument()
            .root
            .toString()
    val feedback = MutableStateFlow<String?>(null)

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

    override suspend fun saveToken(value: CharArray): TokenSaveOutcome {
        value.fill('\u0000')
        return tokenOutcome
    }

    override suspend fun saveConfig(payload: String): List<ConfigValidationError> = emptyList()

    override suspend fun exportConfig(): ByteArray = ByteArray(0)

    override suspend fun importConfig(payload: ByteArray): List<ConfigValidationError> = emptyList()

    override suspend fun formattedConfig(): String = formatted

    override fun configurationFeedback(): StateFlow<String?> = feedback

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
