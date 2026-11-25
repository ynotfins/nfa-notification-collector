package com.nfaalerts.collector

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import com.nfaalerts.collector.capture.ActiveNotificationCatchUp
import com.nfaalerts.collector.capture.CaptureDropReason
import com.nfaalerts.collector.capture.CapturePath
import com.nfaalerts.collector.capture.DurableCaptureDispatcher
import com.nfaalerts.collector.capture.LightweightPostedNotification
import com.nfaalerts.collector.capture.ListenerStatusRepository
import com.nfaalerts.collector.capture.NfaNotificationListenerService
import com.nfaalerts.collector.capture.NotificationCaptureProcessor
import com.nfaalerts.collector.capture.PostedNotificationCallback
import com.nfaalerts.collector.capture.ShadeCaptureLookup
import com.nfaalerts.collector.capture.ShadeCaptureState
import com.nfaalerts.collector.capture.ShadeDismisser
import com.nfaalerts.collector.capture.ShadeEvent
import com.nfaalerts.collector.capture.ShadeHygiene
import com.nfaalerts.collector.capture.ShadeNotification
import com.nfaalerts.collector.capture.ShadePolicy
import com.nfaalerts.collector.capture.ShadeReconcilePace
import com.nfaalerts.collector.capture.ShadeSweepResult
import com.nfaalerts.collector.config.AtomicCollectorConfigStore
import com.nfaalerts.collector.config.CollectorConfigCodec
import com.nfaalerts.collector.config.ConfigLoadResult
import com.nfaalerts.collector.config.DefaultSources
import com.nfaalerts.collector.config.InstalledAppRepository
import com.nfaalerts.collector.config.JsonSourceSelectionStore
import com.nfaalerts.collector.config.SelectionLoadState
import com.nfaalerts.collector.config.SelectionUpdate
import com.nfaalerts.collector.config.SourceSelectionRepository
import com.nfaalerts.collector.contract.ContractSyncCoordinator
import com.nfaalerts.collector.contract.PhoneAlertsContractCatalog
import com.nfaalerts.collector.data.CollectorStatusAggregate
import com.nfaalerts.collector.data.DeliveryState
import com.nfaalerts.collector.data.NfaCollectorDatabase
import com.nfaalerts.collector.delivery.BearerLoad
import com.nfaalerts.collector.delivery.DeliveryCoordinator
import com.nfaalerts.collector.delivery.DeliveryWorkScheduler
import com.nfaalerts.collector.delivery.DrainPaceLimiter
import com.nfaalerts.collector.delivery.IngestTransport
import com.nfaalerts.collector.delivery.OkHttpIngestClient
import com.nfaalerts.collector.delivery.RoomDeliveryStore
import com.nfaalerts.collector.delivery.RuntimeDeliverySettings
import com.nfaalerts.collector.diagnostics.DiagnosticRepository
import com.nfaalerts.collector.diagnostics.RoomDiagnosticStore
import com.nfaalerts.collector.security.AndroidKeystoreBearerStore
import com.nfaalerts.collector.security.BearerLoadState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import java.lang.ref.WeakReference
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class NfaCollectorApp : Application() {
    lateinit var appContainer: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        appContainer = AppContainer(this)
        appContainer.initializeOnIo()
        requestCollectorListenerRebind(this)
        CollectorReliabilityService.update(this)
        CaptureKeepAliveScheduler.ensure(this)
        observeConnectivity()
    }

    private fun observeConnectivity() {
        val manager = getSystemService(ConnectivityManager::class.java) ?: return
        appContainer.setNetworkAvailable(manager.activeNetwork != null)
        runCatching {
            manager.registerDefaultNetworkCallback(
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        appContainer.onConnectivityAvailable()
                    }

                    override fun onLost(network: Network) {
                        appContainer.onConnectivityLost()
                    }

                    override fun onUnavailable() {
                        appContainer.onConnectivityLost()
                    }
                },
            )
        }
    }
}

class AppContainer(
    application: Application,
    private val databaseFactory: () -> NfaCollectorDatabase = {
        NfaCollectorDatabase.create(application.applicationContext)
    },
    val deliveryScheduler: DeliveryWorkScheduler = DeliveryWorkScheduler(application.applicationContext),
    private val clock: () -> Long = System::currentTimeMillis,
    internal val configStore: AtomicCollectorConfigStore = AtomicCollectorConfigStore(application.applicationContext),
) {
    private val appContext = application.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutableLastDrainAt = MutableStateFlow<Long?>(null)
    val lastDrainAt: StateFlow<Long?> = mutableLastDrainAt.asStateFlow()
    val sourceSelections =
        SourceSelectionRepository(JsonSourceSelectionStore(configStore, application.applicationContext))
    val installedApps = InstalledAppRepository(application.packageManager)
    val listenerStatus = ListenerStatusRepository()
    private val shadeAlertNotifier = ShadeAlertNotifier(application)
    private val shadeSweepInFlight = AtomicBoolean(false)

    @Volatile
    private var cachedShadePolicy = ShadePolicy()

    private val allowlistRejectLock = Any()
    private var pendingAllowlistRejectCount = 0
    private var lastAllowlistRejectFlushAtEpochMillis = 0L

    @Volatile
    private var networkAvailable: Boolean = false

    private val database: NfaCollectorDatabase by lazy(databaseFactory)
    private val diagnostics by lazy { DiagnosticRepository(RoomDiagnosticStore(database.diagnosticsDao())) }
    internal val bearerStore = AndroidKeystoreBearerStore(application.applicationContext)
    private val deliveryStore by lazy { RoomDeliveryStore(database) }
    private val ingestClient =
        OkHttpIngestClient(
            OkHttpClient
                .Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build(),
        )
    val deliveryCoordinator: DeliveryCoordinator by lazy {
        DeliveryCoordinator(
            store = deliveryStore,
            settings = ::runtimeDeliverySettings,
            transport = IngestTransport(ingestClient::send),
            scheduler = deliveryScheduler,
            paceLimiter = DrainPaceLimiter(),
            onDelivered = { requestShadeHygiene() },
        )
    }
    private val contractSync by lazy {
        ContractSyncCoordinator.create(application.applicationContext) {
            runCatching {
                application.applicationContext.assets
                    .open("INGEST-CONTRACT.md")
                    .bufferedReader()
                    .use { it.readText() }
            }.getOrDefault("")
        }
    }
    private val captureProcessor: NotificationCaptureProcessor by lazy {
        NotificationCaptureProcessor.createAndroid(
            packageManager = application.packageManager,
            captureWriteDao = { database.captureWriteDao() },
            onPersisted = ::startImmediateDeliveryAfterPersist,
            contractVersionProvider = { contractSync.activeContractVersion() },
        )
    }

    val postedNotificationCallback =
        PostedNotificationCallback(
            allowlistProvider = sourceSelections::snapshot,
            selectionLoadStateProvider = { sourceSelections.loadState.value },
            eventIdFactory = { UUID.randomUUID().toString() },
            clock = System::currentTimeMillis,
            dispatcher =
                DurableCaptureDispatcher(
                    processor = captureProcessor::process,
                    diagnostics = listenerStatus,
                ),
            initializationDiagnostics = listenerStatus,
            dropLedger = ::noteCaptureDrop,
        )

    private val shadeHygiene: ShadeHygiene by lazy {
        ShadeHygiene(
            lookup =
                ShadeCaptureLookup { packageName, notifications ->
                    val rows =
                        database
                            .captureReadDao()
                            .deliveryStatesForPackage(packageName, notifications.map(ShadeNotification::key).distinct())
                    val byIdentity =
                        rows.associateBy {
                            ShadeNotification.identityOf(it.notificationKey, it.postTimeEpochMillis)
                        }
                    notifications.associate { notification ->
                        val state =
                            when (byIdentity[notification.identity]?.state) {
                                DeliveryState.SENT -> ShadeCaptureState.SENT
                                null -> ShadeCaptureState.NOT_PERSISTED
                                else -> ShadeCaptureState.PERSISTED
                            }
                        notification.identity to state
                    }
                },
            dismisser =
                ShadeDismisser { key ->
                    val listener = activeListenerRef?.get() ?: return@ShadeDismisser false
                    runCatching { listener.cancelNotification(key) }.isSuccess
                },
            policyProvider = { cachedShadePolicy },
            onEvent = ::onShadeEvent,
            recount = {
                delay(SHADE_RECOUNT_SETTLE_MS)
                activeShadeNotifications()
            },
        )
    }

    @Volatile
    private var activeListenerRef: WeakReference<NfaNotificationListenerService>? = null

    fun registerActiveListener(listener: NfaNotificationListenerService) {
        activeListenerRef = WeakReference(listener)
    }

    fun unregisterActiveListener(listener: NfaNotificationListenerService) {
        if (activeListenerRef?.get() === listener) {
            activeListenerRef = null
        }
    }

    fun requestConnectedShadeReconcile() {
        activeListenerRef?.get()?.reconcileActiveNotifications()
    }

    internal fun activeShadeNotifications(): List<ShadeNotification> =
        activeListenerRef?.get()?.activeShadeNotifications().orEmpty()

    fun shadeCatchUpActive(): Boolean =
        ShadeReconcilePace.degraded(listenerStatus.state.value.connected, networkAvailable)

    fun awaitSelectionsReady() {
        if (sourceSelections.loadState.value !is SelectionLoadState.Loading) return
        runBlocking {
            withTimeoutOrNull(SELECTION_READY_TIMEOUT_MS) {
                while (sourceSelections.loadState.value is SelectionLoadState.Loading) {
                    delay(SELECTION_READY_POLL_MS)
                }
            }
        }
    }

    fun noteCaptureDrop(
        reason: CaptureDropReason,
        packageName: String,
        capturePath: String,
    ) {
        if (reason == CaptureDropReason.ALLOWLIST_REJECT && capturePath == CapturePath.SHADE) return
        listenerStatus.onDropped(reason, packageName, capturePath)
        when (reason) {
            CaptureDropReason.CATCH_UP_ALREADY_SAVED -> {
                return
            }

            CaptureDropReason.ALLOWLIST_REJECT -> {
                recordAggregatedAllowlistReject(packageName)
                return
            }

            else -> {
                Unit
            }
        }
        scope.launch(Dispatchers.IO) {
            recordCaptureDrop(reason, packageName, capturePath, count = 1)
        }
    }

    /**
     * A non-selected package (gptos.intelligence.assistant) rejects about once a
     * second. One row per event filled the 2,000-row diagnostic cap in minutes
     * and evicted the selected-source evidence. Keep the in-memory counter exact
     * and persist a bounded aggregate row instead.
     */
    private fun recordAggregatedAllowlistReject(packageName: String) {
        val flushCount =
            synchronized(allowlistRejectLock) {
                pendingAllowlistRejectCount += 1
                val now = clock()
                if (now - lastAllowlistRejectFlushAtEpochMillis < ALLOWLIST_AGGREGATE_INTERVAL_MS) {
                    return
                }
                lastAllowlistRejectFlushAtEpochMillis = now
                pendingAllowlistRejectCount.also { pendingAllowlistRejectCount = 0 }
            }
        scope.launch(Dispatchers.IO) {
            runCatching {
                recordCaptureDrop(
                    CaptureDropReason.ALLOWLIST_REJECT,
                    packageName = packageName,
                    capturePath = CapturePath.LIVE,
                    count = flushCount,
                )
            }
        }
    }

    fun setNetworkAvailable(available: Boolean) {
        networkAvailable = available
    }

    fun noteListenerConnected() {
        listenerStatus.onListenerConnected()
        scope.launch(Dispatchers.IO) {
            runCatching { diagnostics.record("LISTENER_CONNECTED", mapOf("listener" to "CONNECTED")) }
        }
    }

    fun noteListenerDisconnected() {
        listenerStatus.onListenerDisconnected()
        scope.launch(Dispatchers.IO) {
            runCatching { diagnostics.record("LISTENER_DISCONNECTED", mapOf("listener" to "DISCONNECTED")) }
        }
    }

    fun initializeOnIo() {
        scope.launch(Dispatchers.IO) {
            restoreListenerTimestamps()
            sourceSelections.load()
            restoreDefaultSources()
            refreshShadePolicy()
            postedNotificationCallback.onSelectionLoadCompleted()
            runCatching { contractSync.maybeCheck(force = true) }
            recoverDeliveryOnStartup()
        }
    }

    private suspend fun restoreListenerTimestamps() {
        val rows = runCatching { database.diagnosticsDao().recent(200) }.getOrDefault(emptyList())
        listenerStatus.noteRestoredTimestamps(
            lastConnectedAtEpochMillis =
                rows.firstOrNull { it.eventCode == "LISTENER_CONNECTED" }?.createdAtEpochMillis,
            lastDisconnectedAtEpochMillis =
                rows.firstOrNull { it.eventCode == "LISTENER_DISCONNECTED" }?.createdAtEpochMillis,
        )
    }

    suspend fun recoverDeliveryOnStartup() {
        val now = clock()
        database.deliveryDao().recoverStaleSending(now)
        relevantRevision()?.let { database.deliveryDao().requeuePausedAuth(it, now) }
        database.deliveryDao().nextDueAtEpochMillis()?.let(deliveryScheduler::ensureScheduled)
        runDeliveryMaintenance()
    }

    suspend fun onRelevantConfigurationChanged() {
        refreshShadePolicy()
        val now = clock()
        relevantRevision()?.let { database.deliveryDao().requeuePausedAuth(it, now) }
        database.deliveryDao().nextDueAtEpochMillis()?.let(deliveryScheduler::ensureScheduled)
    }

    suspend fun nextDeliveryDueAt(): Long? = database.deliveryDao().nextDueAtEpochMillis()

    internal suspend fun recentDeliveryInspection() = database.captureReadDao().recentDeliveryInspection(100)

    internal suspend fun capturedCountByTagPrefix(
        packageName: String,
        tagPrefix: String,
    ): Int = database.captureReadDao().captureCountByTagPrefix(packageName, tagPrefix)

    internal suspend fun deleteCapturedByTagPrefix(
        packageName: String,
        tagPrefix: String,
    ): Int = database.captureWriteDao().deleteCapturesByTagPrefix(packageName, tagPrefix)

    internal fun deliveryInspectionFlow() = database.captureReadDao().recentDeliveryInspectionFlow(100)

    internal fun diagnosticInspectionFlow() = database.diagnosticsDao().recentFlow(100)

    internal fun collectorStatus(): Flow<CollectorStatusAggregate> = database.captureReadDao().collectorStatus()

    internal suspend fun deliveryEnvelopeForUi(eventId: String): String? =
        database.captureReadDao().capture(eventId)?.envelopeJson

    internal suspend fun retryDeliveryFromUi(eventId: String): Boolean {
        val changed = database.deliveryDao().retryFromOperator(eventId, clock()) == 1
        if (changed) deliveryScheduler.ensureScheduled(clock())
        return changed
    }

    internal suspend fun flushDeliveryFromUi(): Int {
        val now = clock()
        database.deliveryDao().recoverStaleSending(now)
        database.deliveryDao().expediteRetryWait(now)
        deliveryScheduler.ensureScheduled(now)
        return runDeliveryDrain("manual-$now", maximumClaims = 256)
    }

    fun onConnectivityAvailable() {
        val wasDown = !networkAvailable
        networkAvailable = true
        val now = clock()
        deliveryScheduler.ensureScheduled(now)
        if (wasDown) requestConnectedShadeReconcile()
        scope.launch { runDeliveryDrain("connectivity-$now", maximumClaims = 32) }
    }

    fun onConnectivityLost() {
        networkAvailable = false
        requestConnectedShadeReconcile()
    }

    suspend fun runDeliveryDrain(
        owner: String,
        maximumClaims: Int = 32,
    ): Int =
        try {
            deliveryCoordinator.drainAvailable(owner, maximumClaims)
        } finally {
            mutableLastDrainAt.value = clock()
        }

    internal suspend fun exportConfigForUi(): ByteArray = configStore.exportPayload()

    internal suspend fun exportDiagnosticsForUi(): ByteArray =
        withContext(Dispatchers.Default) {
            val rows = database.diagnosticsDao().recent(2_000)
            JsonArray(
                rows.map { row ->
                    JsonObject(
                        mapOf(
                            "diagnosticId" to JsonPrimitive(row.diagnosticId),
                            "createdAt" to JsonPrimitive(row.createdAtEpochMillis),
                            "eventCode" to JsonPrimitive(row.eventCode),
                            "safeDetails" to row.safeDetailsElement(),
                        ),
                    )
                },
            ).toString().toByteArray(StandardCharsets.UTF_8)
        }

    suspend fun recoverExpiredSending(): Int = database.deliveryDao().recoverStaleSending(clock())

    suspend fun runDeliveryMaintenance() {
        val document = loadConfigDocument().first
        val now = clock()
        val sentCutoff =
            now -
                TimeUnit.DAYS.toMillis(
                    document.config.retention.sentDays
                        .toLong(),
                )
        database.retentionDao().pruneSent(sentCutoff, document.config.retention.maxSentRows, now)
        val diagnosticCutoff =
            now -
                TimeUnit.DAYS.toMillis(
                    document.config.diagnostics.retentionDays
                        .toLong(),
                )
        database
            .diagnosticsDao()
            .prune(
                diagnosticCutoff,
                document.config.diagnostics.maxRows,
                DiagnosticRepository.PROTECTED_EVENT_CODES.toList(),
                DiagnosticRepository.PROTECTED_MAX_ROWS,
            )
    }

    private fun startImmediateDeliveryAfterPersist(
        eventId: String,
        capturePath: String,
        packageName: String,
    ) {
        val now = clock()
        deliveryScheduler.ensureScheduled(now)
        scope.launch(Dispatchers.IO) {
            runCatching {
                diagnostics.record(
                    "CAPTURE_PERSISTED",
                    mapOf(
                        "eventId" to eventId,
                        "state" to capturePath,
                        "packageName" to packageName,
                    ),
                )
            }
        }
        scope.launch { runDeliveryDrain("immediate-$eventId", maximumClaims = 1) }
    }

    /**
     * Dismisses delivered notifications and keeps every selected package under
     * Android's 50-notification ceiling. Runs off the listener thread and is
     * single-flight: a burst of posts coalesces into one sweep.
     */
    fun requestShadeHygiene() {
        if (activeListenerRef?.get() == null) return
        if (!shadeSweepInFlight.compareAndSet(false, true)) return
        scope.launch(Dispatchers.IO) {
            try {
                val active = activeShadeNotifications()
                shadeHygiene.sweep(sourceSelections.snapshot().packageNames, active)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Shade hygiene must never take capture down.
            } finally {
                shadeSweepInFlight.set(false)
            }
        }
    }

    private suspend fun onShadeEvent(event: ShadeEvent) {
        when (event) {
            is ShadeEvent.Swept -> {
                listenerStatus.onShadeSweep(event.result)
                shadeAlertNotifier.publish(
                    event.result.alertPackages.associateWith { event.result.observedCounts[it] ?: 0 },
                )
            }

            is ShadeEvent.Dismissed -> {
                runCatching {
                    diagnostics.record(
                        "SHADE_DISMISSED",
                        mapOf(
                            "packageName" to event.packageName,
                            "dismissed" to event.count,
                            "sent" to event.sentCount,
                            "persisted" to event.persistedCount,
                            "state" to "DISMISSED",
                        ),
                    )
                }
            }

            is ShadeEvent.CapGuard -> {
                runCatching {
                    diagnostics.record(
                        "SHADE_CAP_GUARD",
                        mapOf(
                            "packageName" to event.packageName,
                            "active" to event.beforeCount,
                            "remaining" to event.afterCount,
                            "state" to "CAP_GUARD",
                        ),
                    )
                }
            }

            is ShadeEvent.Alert -> {
                runCatching {
                    diagnostics.record(
                        "SHADE_ALERT",
                        mapOf(
                            "packageName" to event.packageName,
                            "active" to event.count,
                            "state" to "ALERT",
                        ),
                    )
                }
            }
        }
    }

    private suspend fun restoreDefaultSources() {
        if (sourceSelections.loadState.value !is SelectionLoadState.Ready) return
        val bnnInstalled =
            runCatching { appContext.packageManager.getPackageInfo(DefaultSources.BNN_PACKAGE, 0) }.isSuccess
        val missing = DefaultSources.missingBnn(sourceSelections.snapshot().selections, bnnInstalled) ?: return
        if (sourceSelections.upsert(missing) != SelectionUpdate.Accepted) return
        runCatching {
            diagnostics.record(
                "SOURCE_DEFAULT_RESTORED",
                mapOf(
                    "packageName" to missing.packageName,
                    "source" to missing.sourceId,
                    "state" to "RESTORED",
                ),
            )
        }
    }

    private suspend fun refreshShadePolicy() {
        cachedShadePolicy =
            ShadePolicy(
                keepNewestPerPackage =
                    loadConfigDocument()
                        .first.config.shade.keepNewestPerPackage,
            )
    }

    suspend fun reconcileActiveNotifications(notifications: List<LightweightPostedNotification>): Int {
        val catchUp =
            ActiveNotificationCatchUp(
                alreadyCaptured = { packageName, key, postTimeEpochMillis ->
                    database.captureReadDao().existsIdentity(packageName, key, postTimeEpochMillis)
                },
                onMissing = postedNotificationCallback::onNotificationPosted,
                diagnostics = listenerStatus,
                dropLedger = ::noteCaptureDrop,
            )
        val result = catchUp.reconcile(notifications)
        if (result.skippedExisting > 0) {
            scope.launch(Dispatchers.IO) {
                recordCaptureDrop(
                    CaptureDropReason.CATCH_UP_ALREADY_SAVED,
                    packageName = null,
                    capturePath = CapturePath.SHADE,
                    count = result.skippedExisting,
                )
            }
        }
        return result.imported
    }

    private suspend fun recordCaptureDrop(
        reason: CaptureDropReason,
        packageName: String?,
        capturePath: String,
        count: Int,
    ) {
        val values =
            linkedMapOf<String, Any?>(
                "errorCode" to reason.name,
                "state" to capturePath,
                "queue" to count,
            )
        if (packageName != null && DIAGNOSTIC_PACKAGE.matches(packageName)) {
            values["packageName"] = packageName
        }
        runCatching { diagnostics.record("CAPTURE_DROPPED", values) }
    }

    private suspend fun runtimeDeliverySettings(): RuntimeDeliverySettings {
        val (document, configValid) = loadConfigDocument()
        val bearerState = if (configValid) bearerStore.load() else BearerLoadState.Missing
        val bearer =
            when (bearerState) {
                is BearerLoadState.Present -> BearerLoad.Present(bearerState.value)
                BearerLoadState.Missing -> BearerLoad.Missing
                BearerLoadState.TemporaryFailure -> BearerLoad.TemporaryFailure
            }
        val bearerRevision = (bearerState as? BearerLoadState.Present)?.revision ?: 0L
        return RuntimeDeliverySettings(
            endpoint = document.config.activeEndpoint,
            relevantRevision = relevantRevision(document, bearerRevision),
            bearer = bearer,
            deviceId = document.config.deviceId,
            connectTimeoutMs = document.config.delivery.connectTimeoutMs,
            readTimeoutMs = document.config.delivery.readTimeoutMs,
            initialBackoffMs = document.config.delivery.initialBackoffMs,
            maxBackoffMs = document.config.delivery.maxBackoffMs,
        )
    }

    private suspend fun relevantRevision(): Long? {
        val bearerRevision = bearerStore.revisionFingerprint() ?: return null
        return relevantRevision(loadConfigDocument().first, bearerRevision)
    }

    private fun relevantRevision(
        document: com.nfaalerts.collector.config.CollectorConfigDocument,
        bearerRevision: Long,
    ): Long {
        val relevant =
            listOf(
                document.config.deviceId,
                document.config.activeEndpointProfile,
                document.config.activeEndpoint.baseUrl,
                document.config.activeEndpoint.ingestPath,
                bearerRevision.toString(),
            ).joinToString("\u0000")
        val digest = MessageDigest.getInstance("SHA-256").digest(relevant.encodeToByteArray())
        return ByteBuffer.wrap(digest, 0, java.lang.Long.BYTES).long
    }

    private suspend fun loadConfigDocument(): Pair<com.nfaalerts.collector.config.CollectorConfigDocument, Boolean> =
        when (val configLoad = configStore.load()) {
            is ConfigLoadResult.Loaded -> configLoad.document to true

            ConfigLoadResult.Missing -> CollectorConfigCodec().defaultDocument() to true

            is ConfigLoadResult.Invalid,
            ConfigLoadResult.IoFailure,
            -> CollectorConfigCodec().defaultDocument() to false
        }

    private companion object {
        const val SELECTION_READY_TIMEOUT_MS = 5_000L
        const val SELECTION_READY_POLL_MS = 20L
        const val SHADE_RECOUNT_SETTLE_MS = 250L
        const val ALLOWLIST_AGGREGATE_INTERVAL_MS = 5 * 60_000L
        val DIAGNOSTIC_PACKAGE = Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+")
    }
}

private fun com.nfaalerts.collector.data.DiagnosticEventEntity.safeDetailsElement(): JsonElement =
    runCatching { Json.parseToJsonElement(safeDetailsJson) }.getOrElse { JsonPrimitive(safeDetailsJson) }
