package com.nfaalerts.collector.ui

import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import com.nfaalerts.collector.MainActivity
import com.nfaalerts.collector.config.ConfigValidationError
import com.nfaalerts.collector.config.InstalledApp
import com.nfaalerts.collector.config.SourceSelection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
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

        composeRule.onNodeWithText("Setup health").assertIsDisplayed()
        composeRule.onNodeWithText("Action needed").assertIsDisplayed()
        composeRule.onNodeWithTag("status-list").performScrollToNode(hasText("Send now"))
        composeRule.onNodeWithText("Send now").assertIsDisplayed()
        composeRule.onNodeWithTag("status-list").performScrollToNode(hasText("Tips"))
        composeRule.onNodeWithText("Tips").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Open Sources").performClick()
        composeRule.onNodeWithText("Sources (1 / 10)").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Open Queue").performClick()
        composeRule.onNodeWithText("Queue & delivery").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Open Settings").performClick()
        composeRule.onNodeWithText("Connection settings").assertIsDisplayed()
    }

    @Test
    fun deliveryFlushRunsTheOrderedOutboxDrain() {
        val repository = FakeCollectorUiRepository()
        composeRule.activity.setContent {
            CollectorHomeScreen(
                repository = repository,
                openNotificationAccessSettings = {},
                openBatterySettings = {},
            )
        }

        composeRule.onNodeWithContentDescription("Open Queue").performClick()
        composeRule.onNodeWithText("Send queued alerts now").performClick()
        composeRule.onNodeWithText("Flush completed: 3 queue item(s) attempted in capture order.").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(1, repository.flushCalls) }
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

        composeRule.onNodeWithContentDescription("Open Settings").performClick()
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
    fun tokenShowHideValidationAndSavedStateStayTruthfulAfterReopen() {
        val repository = FakeCollectorUiRepository()
        val syntheticToken = "a".repeat(41) + "-_"
        composeRule.activity.setContent {
            CollectorHomeScreen(
                repository = repository,
                openNotificationAccessSettings = {},
                openBatterySettings = {},
            )
        }

        composeRule.onNodeWithContentDescription("Open Settings").performClick()
        composeRule.onNodeWithText("Enter token").performClick()
        composeRule.onNodeWithTag("bearer-token-editor").performTextInput("short")
        composeRule.onNodeWithText("Save token").performClick()
        composeRule.onNodeWithText("Token must be exactly 43 characters. Current length: 5.").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(null, repository.savedTokenLength) }

        composeRule.onNodeWithTag("bearer-token-editor").performTextClearance()
        composeRule.onNodeWithTag("bearer-token-editor").performTextInput(syntheticToken)
        composeRule.onNodeWithText("Show token").performClick()
        composeRule.onNodeWithTag("bearer-token-editor").assertTextContains(syntheticToken)
        composeRule.onNodeWithText("Hide token").assertIsDisplayed()
        composeRule.onNodeWithText("Save token").performClick()

        composeRule.onNodeWithText("Token saved · last updated", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Replace token").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(43, repository.savedTokenLength) }

        composeRule.activity.setContent {
            CollectorHomeScreen(
                repository = repository,
                openNotificationAccessSettings = {},
                openBatterySettings = {},
            )
        }
        composeRule.onNodeWithContentDescription("Open Settings").performClick()
        composeRule.onNodeWithText("Token saved · last updated", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Replace token").performClick()
        composeRule.onNodeWithText("Replace saved token").assertIsDisplayed()
        composeRule.onNodeWithTag("bearer-token-editor").performTextInput("replacement-not-saved")
        composeRule.onNodeWithText("Cancel").performClick()
        composeRule.onNodeWithText("Token saved · last updated", substring = true).assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(43, repository.savedTokenLength) }
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
        composeRule.onNodeWithText("Action needed").assertIsDisplayed()

        composeRule.runOnIdle {
            repository.mutableState.value =
                repository.mutableState.value.withReadiness(true, true, true, true, 1)
        }

        composeRule
            .onNodeWithText("All required collector checks are green")
            .assertIsDisplayed()
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

        composeRule.onNodeWithContentDescription("Open Home").performClick()
        repository.mutableState.value = repository.mutableState.value.withReadiness(true, true, false, true, 0)
        composeRule.onNodeWithText("Enter token").performClick()
        composeRule.onNodeWithText("Enter bearer token").assertIsDisplayed()
        composeRule.onNodeWithText("Cancel").performClick()

        composeRule.onNodeWithContentDescription("Open Home").performClick()
        repository.mutableState.value = repository.mutableState.value.withReadiness(true, true, true, true, 0)
        composeRule.onNodeWithText("Choose sources").performClick()
        composeRule.onNodeWithText("Sources (1 / 10)").assertIsDisplayed()
    }

    @Test
    fun configuredRequirementsRenderTextualGreenSemanticReadyState() {
        val repository =
            FakeCollectorUiRepository(
                initial =
                    defaultSnapshot()
                        .withReadiness(true, true, true, true, 1)
                        .copy(networkState = "Connected"),
            )
        composeRule.activity.setContent {
            CollectorHomeScreen(
                repository = repository,
                openNotificationAccessSettings = {},
                openBatterySettings = {},
            )
        }

        composeRule
            .onNodeWithText("All required collector checks are green")
            .assertIsDisplayed()
        composeRule
            .onNodeWithTag("collector-ready-container")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Collector ready"))
            .assertIsDisplayed()
        assertEquals(0, repository.verifyCalls)
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

        composeRule.onNodeWithText("Notification access: Unknown", substring = true).assertIsDisplayed()
        composeRule
            .onNodeWithText("Notification access could not be checked safely.", substring = true)
            .assertIsDisplayed()
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

        composeRule.onNodeWithContentDescription("Open Settings").performClick()
        composeRule.onNodeWithText("Enter token").performClick()
        composeRule.onNodeWithTag("bearer-token-editor").performTextInput("a".repeat(41) + "-_")
        composeRule.onNodeWithText("Save token").performClick()

        composeRule.onNodeWithText("Enter bearer token").assertDoesNotExist()
        composeRule
            .onNodeWithText("Token saved, but delivery requeue failed. Re-enter the token or restart the app to retry.")
            .assertIsDisplayed()
        composeRule.runOnIdle {
            assertTrue(
                composeRule.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE == 0,
            )
        }
    }

    @Test
    fun largeFontStatusRemainsScrollableAndNavigationHasTalkBackLabels() {
        composeRule.activity.setContent {
            val deviceDensity = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(deviceDensity.density, fontScale = 2f)) {
                CollectorHomeScreen(
                    repository = FakeCollectorUiRepository(),
                    openNotificationAccessSettings = {},
                    openBatterySettings = {},
                )
            }
        }

        composeRule.onNodeWithContentDescription("Open Home").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Open Sources").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Open Queue").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Open Settings").assertIsDisplayed()
        composeRule.onNodeWithTag("status-list").performScrollToNode(hasText("Network: Unknown"))
        composeRule.onNodeWithText("Network: Unknown").assertIsDisplayed()
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

        composeRule.onNodeWithContentDescription("Open Settings").performClick()
        composeRule.onNodeWithText("Endpoint profile").assertIsDisplayed()
        composeRule.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("device-id-form"))
        composeRule.onNodeWithTag("device-id-form").assertIsDisplayed()
        composeRule.onNodeWithTag("settings-list").performScrollToNode(hasText("Advanced JSON…"))
        composeRule.onNodeWithText("Advanced JSON…").performClick()
        composeRule.onNodeWithTag("settings-list").performScrollToNode(hasText("Validate only"))
        composeRule.onNodeWithText("Validate only").performClick()
        composeRule.onNodeWithTag("settings-list").performScrollToNode(hasText("Configuration is valid."))
        composeRule.onNodeWithText("Configuration is valid.").assertIsDisplayed()
        composeRule.onNodeWithTag("settings-list").performScrollToNode(hasText("Notification Access details"))
        composeRule.onNodeWithText("Notification Access details").performClick()
        composeRule.onNodeWithTag("settings-list").performScrollToNode(hasText("Battery settings details"))
        composeRule.onNodeWithText("Battery settings details").performClick()
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

        composeRule.onNodeWithContentDescription("Open Settings").performClick()
        composeRule.onNodeWithTag("settings-list").performScrollToNode(hasText("Advanced JSON…"))
        composeRule.onNodeWithText("Advanced JSON…").performClick()
        composeRule.onNodeWithTag("settings-list").performScrollToNode(hasText("Reset to approved defaults"))
        composeRule.onNodeWithText("Reset to approved defaults").performClick()

        composeRule.onNodeWithTag("settings-list").performScrollToNode(hasText("Primary phone"))
        composeRule.onNodeWithText("Primary phone").assertIsDisplayed()
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
        composeRule.onNodeWithContentDescription("Open Settings").performClick()

        repository.feedback.value = "/: CONFIG_PAYLOAD_LIMIT — Configuration exceeds 1 MiB."
        composeRule
            .onNodeWithTag("settings-list")
            .performScrollToNode(hasText("/: CONFIG_PAYLOAD_LIMIT — Configuration exceeds 1 MiB."))
        composeRule.onNodeWithText("/: CONFIG_PAYLOAD_LIMIT — Configuration exceeds 1 MiB.").assertIsDisplayed()

        repository.feedback.value = "Configuration export failed."
        composeRule.onNodeWithTag("settings-list").performScrollToNode(hasText("Configuration export failed."))
        composeRule.onNodeWithText("Configuration export failed.").assertIsDisplayed()
    }

    @Test
    fun deliveryRowsUpdateLiveAndEnvelopeOpensInBoundedPagesAfterPrivacyWarning() {
        val repository = FakeCollectorUiRepository()
        repository.envelope = "event-envelope-" + "x".repeat(9_000) + "-tail"
        composeRule.activity.setContent {
            CollectorHomeScreen(
                repository = repository,
                openNotificationAccessSettings = {},
                openBatterySettings = {},
            )
        }

        composeRule.onNodeWithContentDescription("Open Queue").performClick()
        composeRule.onNodeWithText("Queue & delivery").assertIsDisplayed()
        repository.delivery.value =
            listOf(
                DeliveryUiRow(
                    eventId = "event-1",
                    packageName = "us.bnn.newsapp",
                    sourceId = "bnn",
                    state = "RETRY_WAIT",
                    attempts = 2,
                    httpStatus = 503,
                    safeFailure = "HTTP_503",
                    serverId = null,
                    occurredAt = 1234L,
                    redactedPreview = "Notification content redacted",
                ),
            )

        composeRule.onNodeWithTag("delivery-list").performScrollToNode(hasTestTag("delivery-card-event-1"))
        composeRule.onNodeWithText("BNN delivery").assertIsDisplayed()
        composeRule.onNodeWithText("us.bnn.newsapp").assertIsDisplayed()
        composeRule.onNodeWithText("Captured: 1234").assertIsDisplayed()
        composeRule.onNodeWithText("Retry this delivery").performClick()
        composeRule.runOnIdle { assertTrue(repository.retryCalls == listOf("event-1")) }
        composeRule.onNodeWithText("View private local envelope").performClick()
        composeRule.onNodeWithText("Full notification content can be private.", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("I understand").performClick()
        composeRule.onNodeWithText("Envelope page 1 / 2").assertIsDisplayed()
        composeRule.onNodeWithText("Next page").performClick()
        composeRule.onNodeWithText("Envelope page 2 / 2").assertIsDisplayed()
        composeRule.onNodeWithText("-tail", substring = true).assertIsDisplayed()
    }

    @Test
    fun largeFontEnvelopeDialogContentIsScrollableAndActionsStayContextual() {
        val repository = FakeCollectorUiRepository()
        repository.envelope = "event-envelope-" + "x".repeat(9_000) + "-tail"
        repository.delivery.value =
            listOf(
                DeliveryUiRow(
                    eventId = "event-1",
                    packageName = "us.bnn.newsapp",
                    sourceId = "bnn",
                    state = "RETRY_WAIT",
                    attempts = 2,
                    httpStatus = 503,
                    safeFailure = "HTTP_503",
                    serverId = null,
                    occurredAt = 1234L,
                    redactedPreview = "Notification content redacted",
                ),
            )
        composeRule.activity.setContent {
            val deviceDensity = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(deviceDensity.density, fontScale = 2f)) {
                CollectorHomeScreen(
                    repository = repository,
                    openNotificationAccessSettings = {},
                    openBatterySettings = {},
                )
            }
        }

        composeRule.onNodeWithContentDescription("Open Queue").performClick()
        composeRule
            .onNodeWithTag("delivery-list")
            .performScrollToNode(hasContentDescription("Retry delivery event-1"))
        composeRule.onNodeWithContentDescription("Retry delivery event-1").assertIsDisplayed()
        composeRule
            .onNodeWithTag("delivery-list")
            .performScrollToNode(hasContentDescription("View local envelope for event-1"))
        composeRule.onNodeWithContentDescription("View local envelope for event-1").performClick()
        composeRule.onNodeWithText("I understand").performClick()
        composeRule.onNodeWithText("Next page").performClick()
        composeRule.onNodeWithTag("envelope-dialog-list").assertIsDisplayed()
        composeRule
            .onNodeWithTag("envelope-dialog-list")
            .performScrollToNode(hasText("-tail", substring = true))
        composeRule.onNodeWithText("-tail", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Close").assertIsDisplayed()
    }

    @Test
    fun largeFontSettingsProfileActionsStayReachableAtDeviceWidth() {
        var importRequested = false
        var exportRequested = false
        composeRule.activity.setContent {
            val deviceDensity = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(deviceDensity.density, fontScale = 2f)) {
                CollectorHomeScreen(
                    repository = FakeCollectorUiRepository(),
                    openNotificationAccessSettings = {},
                    openBatterySettings = {},
                    requestImport = { importRequested = true },
                    requestExport = { exportRequested = true },
                )
            }
        }

        composeRule.onNodeWithContentDescription("Open Settings").performClick()
        composeRule
            .onNodeWithTag("settings-list")
            .performScrollToNode(hasText("Advanced JSON…"))
        composeRule.onNodeWithText("Advanced JSON…").performClick()
        composeRule
            .onNodeWithTag("settings-list")
            .performScrollToNode(hasText("Validate only"))
        composeRule.onNodeWithText("Validate only").assertIsDisplayed().performClick()
        composeRule
            .onNodeWithTag("settings-list")
            .performScrollToNode(hasText("Save / Apply"))
        composeRule.onNodeWithText("Save / Apply").assertIsDisplayed().performClick()
        composeRule
            .onNodeWithTag("settings-list")
            .performScrollToNode(hasText("Import configuration"))
        composeRule.onNodeWithText("Import configuration").assertIsDisplayed().performClick()
        composeRule.runOnIdle { assertTrue(importRequested) }
        composeRule
            .onNodeWithTag("settings-list")
            .performScrollToNode(hasText("Export configuration"))
        composeRule.onNodeWithText("Export configuration").assertIsDisplayed().performClick()
        composeRule.runOnIdle { assertTrue(exportRequested) }
        composeRule
            .onNodeWithTag("settings-list")
            .performScrollToNode(hasText("Endpoint profile"))
        composeRule.onNodeWithText("Endpoint profile").assertIsDisplayed()
        composeRule
            .onNodeWithTag("settings-list")
            .performScrollToNode(hasText("Custom / edit endpoint…"))
        composeRule.onNodeWithText("Custom / edit endpoint…").performClick()
        composeRule
            .onNodeWithTag("settings-list")
            .performScrollToNode(hasContentDescription("Remove profile tailscale"))
        composeRule.onNodeWithContentDescription("Remove profile tailscale").assertIsDisplayed()
    }

    @Test
    fun diagnosticsListAndExportAreRenderedWithoutCapturedContentOrSecrets() {
        val repository = FakeCollectorUiRepository()
        var exportRequested = false
        repository.diagnostics.value =
            listOf(
                DiagnosticUiRow(
                    diagnosticId = "diag-1",
                    createdAt = 5678L,
                    eventCode = "DELIVERY_RETRY",
                    safeDetails = """{"eventId":"event-1","errorCode":"HTTP_503"}""",
                ),
            )
        composeRule.activity.setContent {
            CollectorHomeScreen(
                repository = repository,
                openNotificationAccessSettings = {},
                openBatterySettings = {},
                requestDiagnosticsExport = { exportRequested = true },
            )
        }

        composeRule.onNodeWithContentDescription("Open Queue").performClick()
        composeRule.onNodeWithTag("delivery-list").performScrollToNode(hasText("Safe diagnostics"))
        composeRule.onNodeWithText("Safe diagnostics").assertIsDisplayed()
        composeRule.onNodeWithTag("delivery-list").performScrollToNode(hasText("DELIVERY_RETRY"))
        composeRule.onNodeWithText("DELIVERY_RETRY").assertIsDisplayed()
        composeRule.onNodeWithText("5678").assertIsDisplayed()
        composeRule.onNodeWithText("""{"eventId":"event-1","errorCode":"HTTP_503"}""").assertIsDisplayed()
        composeRule.onNodeWithTag("delivery-list").performScrollToNode(hasText("Export safe diagnostics"))
        composeRule.onNodeWithText("Export safe diagnostics").performClick()
        composeRule.runOnIdle { assertTrue(exportRequested) }
    }
}

internal class FakeCollectorUiRepository(
    initial: CollectorUiSnapshot = defaultSnapshot(),
) : CollectorUiRepository {
    val mutableState =
        MutableStateFlow(initial)
    override val state: StateFlow<CollectorUiSnapshot> = mutableState
    val delivery = MutableStateFlow<List<DeliveryUiRow>>(emptyList())
    val diagnostics = MutableStateFlow<List<DiagnosticUiRow>>(emptyList())
    var verifyCalls = 0
    var flushCalls = 0
    val retryCalls = mutableListOf<String>()
    var tokenOutcome = TokenSaveOutcome.Saved
    var savedTokenLength: Int? = null
    var envelope = ""
    var diagnosticsExport = ByteArray(0)
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

    override fun deliveryRows(): StateFlow<List<DeliveryUiRow>> = delivery

    override fun diagnosticRows(): StateFlow<List<DiagnosticUiRow>> = diagnostics

    override suspend fun deliveryEnvelope(eventId: String): String? = envelope.takeIf { it.isNotEmpty() }

    override suspend fun exportDiagnostics(): ByteArray = diagnosticsExport

    override suspend fun saveToken(value: CharArray): TokenSaveOutcome {
        savedTokenLength = value.size
        value.fill('\u0000')
        if (tokenOutcome.saved) {
            mutableState.value =
                mutableState.value.copy(
                    readiness = mutableState.value.readiness.copy(bearerSaved = true),
                    bearerSavedAtEpochMillis = 1_790_000_000_000L,
                    bearerRevisionOk = true,
                )
        }
        return tokenOutcome
    }

    override suspend fun saveConfig(payload: String): List<ConfigValidationError> = emptyList()

    override suspend fun saveSettings(
        draft: com.nfaalerts.collector.ui.settings.SettingsDraft,
    ): ConfigMutationOutcome {
        formatted = draft.encodedPayload().decodeToString()
        return ConfigMutationOutcome.Saved
    }

    override suspend fun exportConfig(): ByteArray = ByteArray(0)

    override suspend fun importConfig(payload: ByteArray): List<ConfigValidationError> = emptyList()

    override suspend fun formattedConfig(): String = formatted

    override fun configurationFeedback(): StateFlow<String?> = feedback

    override suspend fun retry(eventId: String): Boolean {
        retryCalls += eventId
        return true
    }

    override suspend fun flushNow(): Int {
        flushCalls += 1
        return 3
    }
}

internal fun defaultSnapshot() =
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
    notificationAccessState = if (access) NotificationAccessState.Granted else NotificationAccessState.Required,
    batteryState = "Unrestricted",
    batteryOptimizationState = BatteryOptimizationState.Exempt,
    backgroundActivityState = BackgroundActivityState.Allowed,
    foregroundNotificationState = ForegroundNotificationState.Granted,
    listenerState = if (access) "Connected" else "Disconnected",
    listenerConnected = access,
    verificationComplete = false,
)
