package com.nfaalerts.collector.ui

import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.nfaalerts.collector.MainActivity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductionPlatformStatusInstrumentedTest {
    @Test
    fun actualAndroidSourceReleasesConnectivityAndBatteryRegistrations() =
        runBlocking {
            val observer = RecordingRegistrationObserver()
            val source =
                AndroidPlatformStatusSource(
                    InstrumentationRegistry.getInstrumentation().targetContext,
                    observer,
                )

            source.connectivityChanges().first()
            source.batteryChanges().first()

            assertEquals(1, observer.registered.count { it == PlatformRegistrationKind.Connectivity })
            assertEquals(1, observer.unregistered.count { it == PlatformRegistrationKind.Connectivity })
            assertEquals(1, observer.registered.count { it == PlatformRegistrationKind.Battery })
            assertEquals(1, observer.unregistered.count { it == PlatformRegistrationKind.Battery })
        }

    @Test
    fun activityResumeRefreshesProductionAndroidPlatformSource() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var before = -1L
            scenario.onActivity { activity ->
                val repository = activity.uiRepositoryForEvidence()
                before = repository.platformRefreshCount
                assertEquals(AndroidPlatformStatusSource::class.java.name, repository.platformSourceType)
            }

            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.moveToState(Lifecycle.State.RESUMED)

            scenario.onActivity { activity ->
                assertTrue(activity.uiRepositoryForEvidence().platformRefreshCount > before)
            }
        }
    }
}

private class RecordingRegistrationObserver : PlatformRegistrationObserver {
    val registered = mutableListOf<PlatformRegistrationKind>()
    val unregistered = mutableListOf<PlatformRegistrationKind>()

    override fun onRegistered(kind: PlatformRegistrationKind) {
        registered += kind
    }

    override fun onUnregistered(kind: PlatformRegistrationKind) {
        unregistered += kind
    }
}

private fun MainActivity.uiRepositoryForEvidence(): AppContainerUiRepository {
    val field = MainActivity::class.java.getDeclaredField("uiRepository")
    field.isAccessible = true
    return field.get(this) as AppContainerUiRepository
}
