package com.nfaalerts.collector

import android.app.Application
import com.nfaalerts.collector.capture.CoroutineCaptureDispatcher
import com.nfaalerts.collector.capture.ListenerStatusRepository
import com.nfaalerts.collector.capture.NotificationCaptureProcessor
import com.nfaalerts.collector.capture.PostedNotificationCallback
import com.nfaalerts.collector.config.InstalledAppRepository
import com.nfaalerts.collector.config.JsonSourceSelectionStore
import com.nfaalerts.collector.config.SourceSelectionRepository
import com.nfaalerts.collector.data.NfaCollectorDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import java.util.UUID

class NfaCollectorApp : Application() {
    lateinit var appContainer: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        appContainer = AppContainer(this)
        appContainer.loadSelectionsBeforeCallbacks()
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
    private val captureProcessor: NotificationCaptureProcessor by lazy {
        NotificationCaptureProcessor.createAndroid(
            packageManager = application.packageManager,
            captureWriteDao = { database.captureWriteDao() },
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
}
