package com.nfaalerts.collector.ui

import android.content.Context
import com.nfaalerts.collector.AppContainer
import com.nfaalerts.collector.capture.ListenerStatus
import com.nfaalerts.collector.config.CollectorConfigCodec
import com.nfaalerts.collector.config.CollectorConfigDocument
import com.nfaalerts.collector.config.ConfigLoadResult
import com.nfaalerts.collector.config.ConfigValidationError
import com.nfaalerts.collector.config.InstalledApp
import com.nfaalerts.collector.config.SelectionLoadState
import com.nfaalerts.collector.config.SourceSelection
import com.nfaalerts.collector.config.SourceSelectionRepository
import com.nfaalerts.collector.data.CollectorStatusAggregate
import com.nfaalerts.collector.security.BearerLoadState
import com.nfaalerts.collector.ui.settings.SettingsDraft
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

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
    private val configFeedback = MutableStateFlow<String?>(null)
    private val configMutationCoordinator =
        ConfigMutationCoordinator(
            invalidateVerification = { verifiedKey.value = null },
            reloadSources = {
                container.sourceSelections.load()
                if (container.sourceSelections.loadState.value !is SelectionLoadState.Ready) {
                    error("SOURCE_RELOAD_PENDING")
                }
            },
            reloadUi = ::refreshStoredStateNow,
            requeue = container::onRelevantConfigurationChanged,
        )
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
                canonicalConfigRevision = config?.revisionHash.orEmpty(),
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
            refreshStoredStateNow()
        }
    }

    private suspend fun refreshStoredStateNow() {
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

    override suspend fun saveConfig(payload: String): List<ConfigValidationError> = saveConfigOutcome(payload).errors()

    override suspend fun saveConfigOutcome(payload: String): ConfigMutationOutcome =
        configMutationCoordinator.save {
            val bytes = withContext(Dispatchers.Default) { payload.encodeToByteArray() }
            try {
                container.configStore.savePayload(bytes)
            } finally {
                bytes.fill(0)
            }
        }

    override suspend fun validateConfig(payload: String): List<ConfigValidationError> =
        withContext(Dispatchers.Default) {
            val bytes = payload.encodeToByteArray()
            try {
                when (val decoded = CollectorConfigCodec().decode(bytes)) {
                    is com.nfaalerts.collector.config.ConfigDecodeResult.Valid -> emptyList()
                    is com.nfaalerts.collector.config.ConfigDecodeResult.Invalid -> decoded.errors
                }
            } finally {
                bytes.fill(0)
            }
        }

    override suspend fun settingsDraft(): SettingsDraft =
        withContext(Dispatchers.Default) {
            SettingsDraft.from(loadConfigDocumentForUi())
        }

    override suspend fun defaultSettingsDraft(): SettingsDraft =
        withContext(Dispatchers.Default) { SettingsDraft.from(CollectorConfigCodec().defaultDocument()) }

    override suspend fun saveSettings(draft: SettingsDraft): ConfigMutationOutcome =
        configMutationCoordinator.save {
            val payload = withContext(Dispatchers.Default) { draft.encodedPayload() }
            try {
                container.configStore.savePayload(payload)
            } finally {
                payload.fill(0)
            }
        }

    override suspend fun exportConfig(): ByteArray = container.configStore.exportPayload()

    override suspend fun importConfig(payload: ByteArray): List<ConfigValidationError> =
        configMutationCoordinator
            .save { container.configStore.importPayload(payload) }
            .also { configFeedback.value = it.importMessage() }
            .errors()

    override suspend fun formattedConfig(): String =
        withContext(Dispatchers.Default) {
            pretty(loadConfigDocumentForUi().root)
        }

    override suspend fun defaultFormattedConfig(): String =
        withContext(Dispatchers.Default) { pretty(CollectorConfigCodec().defaultDocument().root) }

    override fun configurationFeedback(): StateFlow<String?> = configFeedback

    fun reportImportOversize() {
        configFeedback.value = "/: CONFIG_PAYLOAD_LIMIT — Configuration exceeds 1 MiB."
    }

    fun reportImportFailure() {
        configFeedback.value = "Configuration import failed."
    }

    fun reportExportSucceeded() {
        configFeedback.value = "Configuration export completed."
    }

    fun reportExportFailed() {
        configFeedback.value = "Configuration export failed."
    }

    override suspend fun retry(eventId: String): Boolean = container.retryDeliveryFromUi(eventId)

    private fun ConfigMutationOutcome.errors(): List<ConfigValidationError> =
        when (this) {
            is ConfigMutationOutcome.Rejected -> {
                errors
            }

            ConfigMutationOutcome.SaveFailed -> {
                listOf(ConfigValidationError("/", "CONFIG_IO_FAILURE", "Configuration could not be saved."))
            }

            ConfigMutationOutcome.Saved,
            is ConfigMutationOutcome.SavedFollowUpPending,
            -> {
                emptyList()
            }
        }

    private fun ConfigMutationOutcome.importMessage(): String =
        when (this) {
            ConfigMutationOutcome.Saved -> {
                "Configuration import completed."
            }

            is ConfigMutationOutcome.SavedFollowUpPending -> {
                "Configuration imported, but follow-up is pending: $code."
            }

            is ConfigMutationOutcome.Rejected -> {
                errors.joinToString("\n") { it.display() }
            }

            ConfigMutationOutcome.SaveFailed -> {
                "Configuration import failed."
            }
        }

    private suspend fun loadConfigDocumentForUi(): CollectorConfigDocument =
        when (val loaded = container.configStore.load()) {
            is ConfigLoadResult.Loaded -> loaded.document
            ConfigLoadResult.Missing -> CollectorConfigCodec().defaultDocument()
            is ConfigLoadResult.Invalid -> error(loaded.errors.first().code)
            ConfigLoadResult.IoFailure -> error("CONFIG_IO_FAILURE")
        }

    private fun pretty(root: JsonObject): String =
        Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), root)

    private fun ConfigValidationError.display(): String = "$path: $code — $safeMessage"

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
