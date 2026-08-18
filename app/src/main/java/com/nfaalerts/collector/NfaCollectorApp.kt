package com.nfaalerts.collector

import android.app.Application
import com.nfaalerts.collector.capture.CoroutineCaptureDispatcher
import com.nfaalerts.collector.capture.ListenerStatusRepository
import com.nfaalerts.collector.capture.NotificationCaptureProcessor
import com.nfaalerts.collector.capture.PostedNotificationCallback
import com.nfaalerts.collector.config.AtomicCollectorConfigStore
import com.nfaalerts.collector.config.CollectorConfigCodec
import com.nfaalerts.collector.config.ConfigLoadResult
import com.nfaalerts.collector.config.InstalledAppRepository
import com.nfaalerts.collector.config.JsonSourceSelectionStore
import com.nfaalerts.collector.config.SourceSelectionRepository
import com.nfaalerts.collector.data.NfaCollectorDatabase
import com.nfaalerts.collector.delivery.BearerLoad
import com.nfaalerts.collector.delivery.DeliveryCoordinator
import com.nfaalerts.collector.delivery.DeliveryWorkScheduler
import com.nfaalerts.collector.delivery.IngestTransport
import com.nfaalerts.collector.delivery.OkHttpIngestClient
import com.nfaalerts.collector.delivery.RoomDeliveryStore
import com.nfaalerts.collector.delivery.RuntimeDeliverySettings
import com.nfaalerts.collector.security.AndroidKeystoreBearerStore
import com.nfaalerts.collector.security.BearerLoadState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

class NfaCollectorApp : Application() {
    lateinit var appContainer: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        appContainer = AppContainer(this)
        appContainer.loadSelectionsBeforeCallbacks()
        appContainer.startDeliveryRecovery()
    }
}

class AppContainer(
    application: Application,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val sourceSelections =
        SourceSelectionRepository(JsonSourceSelectionStore(application.applicationContext))
    val installedApps = InstalledAppRepository(application.packageManager)
    val listenerStatus = ListenerStatusRepository()
    private val database: NfaCollectorDatabase by lazy {
        NfaCollectorDatabase.create(application.applicationContext)
    }
    private val configStore = AtomicCollectorConfigStore(application.applicationContext)
    private val bearerStore = AndroidKeystoreBearerStore(application.applicationContext)
    val deliveryScheduler = DeliveryWorkScheduler(application.applicationContext)
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
        )
    }
    private val captureProcessor: NotificationCaptureProcessor by lazy {
        NotificationCaptureProcessor.createAndroid(
            packageManager = application.packageManager,
            captureWriteDao = { database.captureWriteDao() },
            onPersisted = ::startImmediateDeliveryAfterPersist,
        )
    }

    val postedNotificationCallback =
        PostedNotificationCallback(
            allowlistProvider = sourceSelections::snapshot,
            eventIdFactory = { UUID.randomUUID().toString() },
            clock = System::currentTimeMillis,
            dispatcher =
                CoroutineCaptureDispatcher(
                    scope = scope,
                    processor = captureProcessor::process,
                    diagnostics = listenerStatus,
                ),
        )

    fun loadSelectionsBeforeCallbacks() {
        runBlocking(Dispatchers.IO) { sourceSelections.load() }
    }

    fun startDeliveryRecovery() {
        scope.launch {
            val now = System.currentTimeMillis()
            database.deliveryDao().recoverStaleSending(now)
            database.deliveryDao().requeuePausedAuth(relevantRevision(), now)
            database.deliveryDao().nextDueAtEpochMillis()?.let(deliveryScheduler::ensureScheduled)
            runDeliveryMaintenance()
        }
    }

    suspend fun onRelevantConfigurationChanged() {
        val now = System.currentTimeMillis()
        database.deliveryDao().requeuePausedAuth(relevantRevision(), now)
        database.deliveryDao().nextDueAtEpochMillis()?.let(deliveryScheduler::ensureScheduled)
    }

    suspend fun nextDeliveryDueAt(): Long? = database.deliveryDao().nextDueAtEpochMillis()

    suspend fun runDeliveryMaintenance() {
        val document = loadConfigDocument().first
        val now = System.currentTimeMillis()
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
        database.diagnosticsDao().prune(diagnosticCutoff, document.config.diagnostics.maxRows)
    }

    private fun startImmediateDeliveryAfterPersist(eventId: String) {
        val now = System.currentTimeMillis()
        deliveryScheduler.ensureScheduled(now)
        scope.launch { deliveryCoordinator.drainAvailable("immediate-$eventId", maximumClaims = 1) }
    }

    private suspend fun runtimeDeliverySettings(): RuntimeDeliverySettings {
        val (document, configValid) = loadConfigDocument()
        val bearerState = if (configValid) bearerStore.load() else BearerLoadState.Missing
        val bearer =
            when (bearerState) {
                is BearerLoadState.Present -> BearerLoad.Present(bearerState.value)
                BearerLoadState.Missing -> BearerLoad.Missing
            }
        return RuntimeDeliverySettings(
            endpoint = document.config.activeEndpoint,
            relevantRevision = relevantRevision(document),
            bearer = bearer,
            deviceId = document.config.deviceId,
            connectTimeoutMs = document.config.delivery.connectTimeoutMs,
            readTimeoutMs = document.config.delivery.readTimeoutMs,
        )
    }

    private suspend fun relevantRevision(): Long = relevantRevision(loadConfigDocument().first)

    private suspend fun relevantRevision(document: com.nfaalerts.collector.config.CollectorConfigDocument): Long {
        val relevant =
            listOf(
                document.config.deviceId,
                document.config.activeEndpointProfile,
                document.config.activeEndpoint.baseUrl,
                document.config.activeEndpoint.ingestPath,
                bearerStore.revisionFingerprint().toString(),
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
}
