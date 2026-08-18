package com.nfaalerts.collector.ui

import android.content.Context
import com.nfaalerts.collector.AppContainer
import com.nfaalerts.collector.capture.ListenerStatus
import com.nfaalerts.collector.config.CollectorConfigCodec
import com.nfaalerts.collector.config.CollectorConfigDocument
import com.nfaalerts.collector.config.ConfigLoadResult
import com.nfaalerts.collector.config.ConfigSaveResult
import com.nfaalerts.collector.config.ConfigValidationError
import com.nfaalerts.collector.config.InstalledApp
import com.nfaalerts.collector.config.SourceSelection
import com.nfaalerts.collector.config.SourceSelectionRepository
import com.nfaalerts.collector.data.CollectorStatusAggregate
import com.nfaalerts.collector.security.BearerLoadState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppContainerUiRepository internal constructor(
    private val container: AppContainer,
    private val scope: CoroutineScope,
    platformSource: PlatformStatusSource,
) : CollectorUiRepository,
    SourcePickerUiAccess {
    constructor(
        context: Context,
        container: AppContainer,
        scope: CoroutineScope,
    ) : this(container, scope, AndroidPlatformStatusSource(context))

    override val sourceSelectionRepository: SourceSelectionRepository = container.sourceSelections
    private val platform = ResumablePlatformState(platformSource)
    private val configState = MutableStateFlow<UiConfigState?>(null)
    private val bearerPresent = MutableStateFlow<Boolean?>(null)
    private val verifiedKey = MutableStateFlow<LocalVerificationKey?>(null)
    private val coreState =
        combine(
            container.collectorStatus(),
            container.listenerStatus.state,
            container.sourceSelections.selections,
            configState,
            bearerPresent,
        ) { aggregate, listener, selections, config, bearer ->
            CoreUiState(
                aggregate,
                listener,
                selections.selections.count { it.enabled },
                selections.selections.size,
                config,
                bearer,
            )
        }

    override val state: StateFlow<CollectorUiSnapshot> =
        combine(
            coreState,
            platform.notificationAccess,
            platform.connectivity,
            platform.battery,
            verifiedKey,
        ) { core, access, connectivity, battery, verified ->
            val config = core.config
            val document = config?.document ?: CollectorConfigCodec().defaultDocument()
            val readiness =
                CollectorReadiness(
                    notificationAccessGranted = access == NotificationAccessState.Granted,
                    endpointIsValid = config?.valid == true,
                    bearerSaved = core.bearerPresent == true,
                    deviceIdIsValid = config?.valid == true && document.config.deviceId.isNotBlank(),
                    enabledSourceCount = core.enabledSourceCount,
                )
            val facts = CollectorStatusFacts.from(core.aggregate)
            val networkLabel = connectivity.label()
            val currentKey =
                LocalVerificationKey(
                    endpoint = document.config.activeEndpoint.baseUrl,
                    deviceId = document.config.deviceId,
                    notificationAccessGranted = readiness.notificationAccessGranted,
                    bearerSaved = readiness.bearerSaved,
                    enabledSourceCount = readiness.enabledSourceCount,
                    connectivityState = networkLabel,
                )
            val verificationComplete =
                readiness.state == CollectorReadinessState.Ready &&
                    connectivity == ConnectivityState.Connected &&
                    verified == currentKey
            CollectorUiSnapshot(
                readiness = readiness,
                endpoint = document.config.activeEndpoint.baseUrl,
                deviceId = document.config.deviceId,
                selectedCount = core.selectedCount,
                queueCount = facts.nonSentCount,
                listenerState = if (core.listener.connected) "Connected" else "Disconnected",
                lastCapture = facts.lastCaptureAtEpochMillis?.toString() ?: "Unknown",
                lastSend = facts.lastSentAtEpochMillis?.toString() ?: "Unknown",
                lastError = facts.latestSafeError ?: "None",
                networkState = networkLabel,
                batteryState = battery.label(),
                totalCount = facts.totalCount,
                queueCountsByState = facts.countsByState,
                serverReceivedAt = facts.lastServerReceivedAt ?: "Unknown",
                verificationComplete = verificationComplete,
                verificationMessage = verificationMessage(readiness, connectivity, verificationComplete),
                loading = config == null || core.bearerPresent == null,
            )
        }.stateIn(
            scope = scope,
            started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 0),
            initialValue = initialSnapshot(),
        )

    init {
        refreshStoredState()
    }

    override fun refreshPlatformState() {
        platform.refresh()
        refreshStoredState()
    }

    override suspend fun verify(): Boolean {
        val current = state.value
        if (
            current.loading ||
            current.readiness.state != CollectorReadinessState.Ready ||
            current.networkState != ConnectivityState.Connected.label()
        ) {
            verifiedKey.value = null
            return false
        }
        verifiedKey.value = current.verificationKey()
        return true
    }

    private fun refreshStoredState() {
        scope.launch(Dispatchers.IO) {
            configState.value =
                when (val loaded = container.configStore.load()) {
                    is ConfigLoadResult.Loaded -> {
                        UiConfigState(loaded.document, true)
                    }

                    ConfigLoadResult.Missing -> {
                        UiConfigState(CollectorConfigCodec().defaultDocument(), true)
                    }

                    is ConfigLoadResult.Invalid, ConfigLoadResult.IoFailure -> {
                        UiConfigState(CollectorConfigCodec().defaultDocument(), false)
                    }
                }
            bearerPresent.value =
                container.bearerStore.load().let { state ->
                    if (state is BearerLoadState.Present) {
                        state.clear()
                        true
                    } else {
                        false
                    }
                }
        }
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
        }.isSuccess.also { if (it) refreshStoredState() }

    override suspend fun saveConfig(payload: String): List<ConfigValidationError> =
        container.configStore
            .savePayload(payload.encodeToByteArray())
            .also { result ->
                if (result is ConfigSaveResult.Saved) {
                    container.onRelevantConfigurationChanged()
                    refreshStoredState()
                }
            }.errors()

    override suspend fun exportConfig(): ByteArray = container.configStore.exportPayload()

    override suspend fun importConfig(payload: ByteArray): List<ConfigValidationError> =
        container.configStore
            .importPayload(payload)
            .also { result ->
                if (result is ConfigSaveResult.Saved) {
                    container.onRelevantConfigurationChanged()
                    refreshStoredState()
                }
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

    private fun CollectorUiSnapshot.verificationKey() =
        LocalVerificationKey(
            endpoint = endpoint,
            deviceId = deviceId,
            notificationAccessGranted = readiness.notificationAccessGranted,
            bearerSaved = readiness.bearerSaved,
            enabledSourceCount = readiness.enabledSourceCount,
            connectivityState = networkState,
        )

    private fun ConnectivityState.label() =
        when (this) {
            ConnectivityState.Connected -> "Connected"
            ConnectivityState.Disconnected -> "Disconnected"
            ConnectivityState.Unknown -> "Unknown"
        }

    private fun BatteryOptimizationState.label() =
        when (this) {
            BatteryOptimizationState.Exempt -> "Unrestricted"
            BatteryOptimizationState.Optimized -> "Optimization active"
            BatteryOptimizationState.Unknown -> "Unknown"
        }

    private fun verificationMessage(
        readiness: CollectorReadiness,
        connectivity: ConnectivityState,
        complete: Boolean,
    ): String =
        when {
            complete -> "Local configuration and connectivity check passed."
            readiness.state != CollectorReadinessState.Ready -> "Complete the required setup steps before verification."
            connectivity == ConnectivityState.Disconnected -> "A network connection is required for verification."
            connectivity == ConnectivityState.Unknown -> "Connectivity could not be checked."
            else -> "Run the safe local verification check."
        }

    private fun initialSnapshot() =
        CollectorUiSnapshot(
            readiness = CollectorReadiness(false, false, false, false, 0),
            endpoint = "Unknown",
            deviceId = "Unknown",
            selectedCount = 0,
            queueCount = 0,
            listenerState = "Unknown",
            loading = true,
        )
}

private data class UiConfigState(
    val document: CollectorConfigDocument,
    val valid: Boolean,
)

private data class CoreUiState(
    val aggregate: CollectorStatusAggregate,
    val listener: ListenerStatus,
    val enabledSourceCount: Int,
    val selectedCount: Int,
    val config: UiConfigState?,
    val bearerPresent: Boolean?,
)
