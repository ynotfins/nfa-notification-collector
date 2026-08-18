package com.nfaalerts.collector.ui

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class PlatformStatusInstrumentedTest {
    @Test
    fun resumeRefreshReadsNotificationAccessAndBatteryAgain() =
        runBlocking {
            val source = RecordingPlatformSource()
            val state = ResumablePlatformState(source)
            assertEquals(NotificationAccessState.Required, state.notificationAccess.value)

            source.access = NotificationAccessState.Granted
            source.battery = BatteryOptimizationState.Exempt
            state.refresh()

            assertEquals(NotificationAccessState.Granted, state.notificationAccess.value)
            assertEquals(BatteryOptimizationState.Exempt, state.battery.first())
            assertEquals(2, source.accessReads)
            assertEquals(2, source.batteryReads)
        }

    @Test
    fun connectivityAndBatteryRegistrationsAreRemovedWhenCollectionStops() =
        runBlocking {
            var connectivityRegistrations = 0
            var connectivityUnregistrations = 0
            var batteryRegistrations = 0
            var batteryUnregistrations = 0

            val connectivity =
                registeredStatusFlow(
                    current = { ConnectivityState.Connected },
                    unknown = ConnectivityState.Unknown,
                ) {
                    connectivityRegistrations += 1
                    { connectivityUnregistrations += 1 }
                }
            val battery =
                registeredStatusFlow(
                    current = { BatteryOptimizationState.Optimized },
                    unknown = BatteryOptimizationState.Unknown,
                ) {
                    batteryRegistrations += 1
                    { batteryUnregistrations += 1 }
                }

            assertEquals(ConnectivityState.Connected, connectivity.first())
            assertEquals(BatteryOptimizationState.Optimized, battery.first())
            assertEquals(1, connectivityRegistrations)
            assertEquals(1, connectivityUnregistrations)
            assertEquals(1, batteryRegistrations)
            assertEquals(1, batteryUnregistrations)
        }
}

private class RecordingPlatformSource : PlatformStatusSource {
    var access = NotificationAccessState.Required
    var battery = BatteryOptimizationState.Optimized
    var accessReads = 0
    var batteryReads = 0

    override fun notificationAccess(): NotificationAccessState {
        accessReads += 1
        return access
    }

    override fun batteryOptimization(): BatteryOptimizationState {
        batteryReads += 1
        return battery
    }

    override fun connectivityChanges(): Flow<ConnectivityState> =
        callbackFlow {
            trySend(ConnectivityState.Connected)
            awaitClose()
        }

    override fun batteryChanges(): Flow<BatteryOptimizationState> =
        callbackFlow {
            trySend(battery)
            awaitClose()
        }
}
