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
import kotlinx.coroutines.flow.drop
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
    private val bearerState = MutableStateFlow<BearerUiState?>(null)
    private val verifiedKey = MutableStateFlow<LiveVerificationFingerprint?>(null)
    private val tokenSaveCoordinator =
        TokenSaveCoordinator(
            saveBearer = container.bearerStore::save,
            invalidateVerification = { verifiedKey.value = null },
            refreshBearer = { refreshBearerState(requirePresent = true) },
            requeue = container::onRelevantConfigurationChanged,
        )
    internal val platformRefreshCount: Long
        get() = platform.refreshCount
    internal val platformSourceType: String
        get() = platform.sourceType
    private val coreState =
        combine(
            container.collectorStatus(),
            container.listenerStatus.state,
            container.sourceSelections.selections,
            configState,
            bearerState,
        ) { aggregate, listener, selections, config, bearer ->
            CoreUiState(
                aggregate,
                listener,
                selections.selections,
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
            val enabledSourceCount = core.selections.count(SourceSelection::enabled)
            val readiness =
                CollectorReadiness(
                    notificationAccessGranted = access == NotificationAccessState.Granted,
                    endpointIsValid = config?.valid == true,
                    bearerSaved = core.bearer?.present == true,
                    deviceIdIsValid = config?.valid == true && document.config.deviceId.isNotBlank(),
                    enabledSourceCount = enabledSourceCount,
                )
            val facts = CollectorStatusFacts.from(core.aggregate)
            val networkLabel = connectivity.label()
            val currentKey =
                if (config?.valid == true && core.bearer?.present == true && core.bearer.revisionFingerprint != null) {
                    LiveVerificationFingerprint.create(
                        canonicalConfigHash = config.revisionHash,
                        activeProfileId = document.config.activeEndpointProfile,
                        baseUrl = document.config.activeEndpoint.baseUrl,
                        ingestPath = document.config.activeEndpoint.ingestPath,
                        deviceId = document.config.deviceId,
                        bearerRevisionFingerprint = core.bearer.revisionFingerprint,
                        sources = core.selections,
                        notificationAccess = access,
                        connectivity = connectivity,
                    )
                } else {
                    null
                }
            val verificationComplete =
                currentKey != null &&
                    readiness.state == CollectorReadinessState.Ready &&
                    connectivity == ConnectivityState.Connected &&
                    verified == currentKey
            CollectorUiSnapshot(
                readiness = readiness,
                endpoint = document.config.activeEndpoint.baseUrl,
                deviceId = document.config.deviceId,
                selectedCount = core.selections.size,
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
                loading = config == null || core.bearer == null,
                notificationAccessState = access,
                connectivityState = connectivity,
                liveVerificationFingerprint = currentKey,
            )
        }.stateIn(
            scope = scope,
            started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 0),
            initialValue = initialSnapshot(),
        )

    init {
        refreshStoredState()
        scope.launch {
            container.sourceSelections.selections
                .drop(1)
                .collect { refreshStoredState() }
        }
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
            current.connectivityState != ConnectivityState.Connected ||
            current.liveVerificationFingerprint == null
        ) {
            verifiedKey.value = null
            return false
        }
        verifiedKey.value = current.liveVerificationFingerprint
        return true
    }

    private fun refreshStoredState() {
        scope.launch(Dispatchers.IO) {
            configState.value =
                when (val loaded = container.configStore.load()) {
                    is ConfigLoadResult.Loaded -> {
                        loaded.document.toUiConfigState(valid = true)
                    }

                    ConfigLoadResult.Missing -> {
                        CollectorConfigCodec().defaultDocument().toUiConfigState(valid = true)
                    }

                    is ConfigLoadResult.Invalid, ConfigLoadResult.IoFailure -> {
                        CollectorConfigCodec().defaultDocument().toUiConfigState(valid = false)
                    }
                }
            refreshBearerState()
        }
    }

    private suspend fun refreshBearerState(requirePresent: Boolean = false) {
        val refreshed =
            container.bearerStore.load().let { state ->
                if (state is BearerLoadState.Present) {
                    val revision = state.revision
                    state.clear()
                    BearerUiState(present = true, revisionFingerprint = revision)
                } else {
                    BearerUiState(
                        present = false,
                        revisionFingerprint = if (state == BearerLoadState.Missing) 0L else null,
                    )
                }
            }
        bearerState.value = refreshed
        if (requirePresent && !refreshed.present) error("BEARER_REVISION_REFRESH_PENDING")
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

    override suspend fun saveToken(value: CharArray): TokenSaveOutcome = tokenSaveCoordinator.save(value)

    override suspend fun saveConfig(payload: String): List<ConfigValidationError> =
        container.configStore
            .savePayload(payload.encodeToByteArray())
            .also { result ->
                if (result is ConfigSaveResult.Saved) {
                    verifiedKey.value = null
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
                    verifiedKey.value = null
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

    private fun ConnectivityState.label() =
        when (this) {
            ConnectivityState.Connected -> "Validated"
            ConnectivityState.Disconnected -> "Not validated"
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
            complete -> {
                "Local configuration and connectivity check passed."
            }

            readiness.state != CollectorReadinessState.Ready -> {
                "Complete the required setup steps before verification."
            }

            connectivity == ConnectivityState.Disconnected -> {
                "A validated network connection is required for verification."
            }

            connectivity == ConnectivityState.Unknown -> {
                "Connectivity could not be checked."
            }

            else -> {
                "Run the safe local verification check."
            }
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

    private fun CollectorConfigDocument.toUiConfigState(valid: Boolean): UiConfigState {
        val payload = CollectorConfigCodec().exportPayload(this)
        return try {
            UiConfigState(this, valid, canonicalConfigHash(payload))
        } finally {
            payload.fill(0)
        }
    }
}

private data class UiConfigState(
    val document: CollectorConfigDocument,
    val valid: Boolean,
    val revisionHash: String,
)

private data class BearerUiState(
    val present: Boolean,
    val revisionFingerprint: Long?,
)

private data class CoreUiState(
    val aggregate: CollectorStatusAggregate,
    val listener: ListenerStatus,
    val selections: List<SourceSelection>,
    val config: UiConfigState?,
    val bearer: BearerUiState?,
)
