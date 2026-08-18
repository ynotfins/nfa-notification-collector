package com.nfaalerts.collector.ui

import android.content.ComponentName
import android.content.Context
import com.nfaalerts.collector.AppContainer
import com.nfaalerts.collector.capture.NfaNotificationListenerService
import com.nfaalerts.collector.capture.NotificationAccessStatus
import com.nfaalerts.collector.config.CollectorConfigCodec
import com.nfaalerts.collector.config.ConfigLoadResult
import com.nfaalerts.collector.config.ConfigSaveResult
import com.nfaalerts.collector.config.ConfigValidationError
import com.nfaalerts.collector.config.InstalledApp
import com.nfaalerts.collector.config.SourceSelection
import com.nfaalerts.collector.config.SourceSelectionRepository
import com.nfaalerts.collector.security.BearerLoadState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AppContainerUiRepository(
    private val context: Context,
    private val container: AppContainer,
) : CollectorUiRepository,
    SourcePickerUiAccess {
    override val sourceSelectionRepository: SourceSelectionRepository = container.sourceSelections

    override suspend fun snapshot(): CollectorUiSnapshot {
        val document =
            when (val loaded = container.configStore.load()) {
                is ConfigLoadResult.Loaded -> {
                    loaded.document to true
                }

                ConfigLoadResult.Missing -> {
                    CollectorConfigCodec().defaultDocument() to true
                }

                is ConfigLoadResult.Invalid, ConfigLoadResult.IoFailure -> {
                    CollectorConfigCodec().defaultDocument() to
                        false
                }
            }
        val bearerSaved =
            container.bearerStore.load().let { state ->
                if (state is BearerLoadState.Present) {
                    state.clear()
                    true
                } else {
                    false
                }
            }
        val selections = container.sourceSelections.snapshot().selections
        val enabled = selections.count { it.enabled }
        val inspection = container.recentDeliveryInspection()
        val listener = container.listenerStatus.state.value
        return CollectorUiSnapshot(
            readiness =
                CollectorReadiness(
                    notificationAccessGranted =
                        NotificationAccessStatus.isGranted(
                            context,
                            ComponentName(context, NfaNotificationListenerService::class.java),
                        ),
                    endpointIsValid = document.second,
                    bearerSaved = bearerSaved,
                    deviceIdIsValid =
                        document.first.config.deviceId
                            .isNotBlank(),
                    enabledSourceCount = enabled,
                ),
            endpoint = document.first.config.activeEndpoint.baseUrl,
            deviceId = document.first.config.deviceId,
            selectedCount = selections.size,
            queueCount = inspection.count { it.state.name != "SENT" },
            listenerState = if (listener.connected) "Connected" else "Disconnected",
            lastCapture = inspection.firstOrNull()?.capturedAtEpochMillis?.toString() ?: "Unknown",
            lastSend =
                inspection.firstOrNull { it.state.name == "SENT" }?.capturedAtEpochMillis?.toString() ?: "Unknown",
            lastError = inspection.firstOrNull { it.lastErrorCode != null }?.lastErrorCode ?: "Unknown",
        )
    }

    override suspend fun installedApps(): List<InstalledApp> = container.installedApps.installedApps()

    override suspend fun sourcePickerApps(): List<InstalledApp> = installedApps()

    override fun sourceIdForPackage(packageName: String): String =
        container.sourceSelections
            .snapshot()
            .selections
            .firstOrNull { it.packageName == packageName }
            ?.sourceId
            ?: "local"

    override suspend fun selectedSources(): List<SourceSelection> = container.sourceSelections.snapshot().selections

    override suspend fun deliveryRows(): List<DeliveryUiRow> =
        container.recentDeliveryInspection().map {
            DeliveryUiRow(
                eventId = it.eventId,
                sourceId = it.sourceId,
                state = it.state.name,
                attempts = it.attemptCount,
                httpStatus = it.lastHttpStatus,
                safeFailure = it.lastErrorCode,
                serverId = it.serverIngestId,
                occurredAt = it.capturedAtEpochMillis,
                redactedPreview = "Notification content redacted",
            )
        }

    override suspend fun deliveryEnvelope(eventId: String): String? = container.deliveryEnvelopeForUi(eventId)

    override suspend fun saveToken(value: CharArray): Boolean =
        runCatching {
            container.bearerStore.save(value)
            container.onRelevantConfigurationChanged()
        }.isSuccess

    override suspend fun saveConfig(payload: String): List<ConfigValidationError> =
        container.configStore
            .savePayload(payload.encodeToByteArray())
            .also { result ->
                if (result is ConfigSaveResult.Saved) container.onRelevantConfigurationChanged()
            }.errors()

    override suspend fun exportConfig(): ByteArray = container.configStore.exportPayload()

    override suspend fun importConfig(payload: ByteArray): List<ConfigValidationError> =
        container.configStore
            .importPayload(payload)
            .also { result ->
                if (result is ConfigSaveResult.Saved) container.onRelevantConfigurationChanged()
            }.errors()

    override suspend fun formattedConfig(): String =
        withContext(Dispatchers.Default) {
            container.exportConfigForUi().decodeToString()
        }

    override suspend fun retry(eventId: String): Boolean = container.retryDeliveryFromUi(eventId)

    private fun ConfigSaveResult.errors(): List<ConfigValidationError> =
        when (this) {
            is ConfigSaveResult.Rejected -> {
                errors
            }

            ConfigSaveResult.IoFailure -> {
                listOf(
                    ConfigValidationError("/", "CONFIG_IO_FAILURE", "Configuration could not be saved."),
                )
            }

            is ConfigSaveResult.Saved -> {
                emptyList()
            }
        }
}
