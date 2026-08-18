package com.nfaalerts.collector.ui

import android.app.Application
import android.os.Looper
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import com.nfaalerts.collector.AppContainer
import com.nfaalerts.collector.config.AtomicCollectorConfigStore
import com.nfaalerts.collector.data.NfaCollectorDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class AppContainerConfigMutationInstrumentedTest {
    private val application = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun realUiRepositoryRunsImportDecodeValidationAndMutationCoordinatorOffMain() =
        runBlocking {
            val name = "ui-config-boundary-${System.nanoTime()}.json"
            val checks = AtomicInteger()
            val store =
                AtomicCollectorConfigStore(
                    context = application,
                    fileName = name,
                    executionChecker = {
                        assertFalse(Looper.myLooper() == Looper.getMainLooper())
                        checks.incrementAndGet()
                    },
                )
            val database = Room.inMemoryDatabaseBuilder(application, NfaCollectorDatabase::class.java).build()
            val container =
                AppContainer(
                    application = application,
                    databaseFactory = { database },
                    configStore = store,
                )
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val repository = AppContainerUiRepository(container, scope, TestPlatformSource())
            try {
                container.sourceSelections.load()
                val payload =
                    """{"configVersion":1,"deviceId":"boundary-device","sources":[],"futureSafe":{"keep":true}}"""
                        .encodeToByteArray()

                val errors = withContext(Dispatchers.Main) { repository.importConfig(payload) }

                assertTrue(errors.isEmpty())
                assertEquals("boundary-device", repository.settingsDraft().deviceId)
                assertTrue(repository.formattedConfig().contains("futureSafe"))
                assertTrue(checks.get() >= 4)
            } finally {
                scope.cancel()
                database.close()
                File(application.filesDir, name).delete()
            }
        }

    private class TestPlatformSource : PlatformStatusSource {
        override fun notificationAccess() = NotificationAccessState.Required

        override fun batteryOptimization() = BatteryOptimizationState.Optimized

        override fun connectivityChanges(): Flow<ConnectivityState> = MutableStateFlow(ConnectivityState.Disconnected)

        override fun batteryChanges(): Flow<BatteryOptimizationState> =
            MutableStateFlow(BatteryOptimizationState.Optimized)
    }
}
