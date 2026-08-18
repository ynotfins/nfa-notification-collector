package com.nfaalerts.collector.ui

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager
import com.nfaalerts.collector.capture.NfaNotificationListenerService
import com.nfaalerts.collector.capture.NotificationAccessStatus
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.merge

enum class NotificationAccessState {
    Granted,
    Required,
    Unknown,
}

enum class ConnectivityState {
    Connected,
    Disconnected,
    Unknown,
}

enum class BatteryOptimizationState {
    Exempt,
    Optimized,
    Unknown,
}

internal enum class PlatformRegistrationKind {
    Connectivity,
    Battery,
}

internal interface PlatformRegistrationObserver {
    fun onRegistered(kind: PlatformRegistrationKind)

    fun onUnregistered(kind: PlatformRegistrationKind)
}

private object NoOpPlatformRegistrationObserver : PlatformRegistrationObserver {
    override fun onRegistered(kind: PlatformRegistrationKind) = Unit

    override fun onUnregistered(kind: PlatformRegistrationKind) = Unit
}

data class NotificationAccessPresentation(
    val label: String,
    val safeExplanation: String,
)

fun NotificationAccessState.presentation(): NotificationAccessPresentation =
    when (this) {
        NotificationAccessState.Granted -> {
            NotificationAccessPresentation("Granted", "Notification access is granted.")
        }

        NotificationAccessState.Required -> {
            NotificationAccessPresentation("Required", "Notification access must be granted.")
        }

        NotificationAccessState.Unknown -> {
            NotificationAccessPresentation("Unknown", "Notification access could not be checked safely.")
        }
    }

internal fun connectivityState(
    hasActiveNetwork: Boolean,
    hasValidatedCapability: Boolean,
): ConnectivityState =
    if (hasActiveNetwork && hasValidatedCapability) {
        ConnectivityState.Connected
    } else {
        ConnectivityState.Disconnected
    }

internal interface PlatformStatusSource {
    fun notificationAccess(): NotificationAccessState

    fun batteryOptimization(): BatteryOptimizationState

    fun connectivityChanges(): Flow<ConnectivityState>

    fun batteryChanges(): Flow<BatteryOptimizationState>
}

internal class ResumablePlatformState(
    private val source: PlatformStatusSource,
) {
    val notificationAccess = MutableStateFlow(source.notificationAccess())
    private val batteryRefresh = MutableStateFlow(source.batteryOptimization())
    val connectivity: Flow<ConnectivityState> = source.connectivityChanges().distinctUntilChanged()
    val battery: Flow<BatteryOptimizationState> =
        merge(batteryRefresh, source.batteryChanges()).distinctUntilChanged()
    val sourceType: String = source::class.java.name
    var refreshCount: Long = 0
        private set

    fun refresh() {
        notificationAccess.value = source.notificationAccess()
        batteryRefresh.value = source.batteryOptimization()
        refreshCount += 1
    }
}

internal fun <T> registeredStatusFlow(
    current: () -> T,
    unknown: T,
    register: (publish: () -> Unit) -> (() -> Unit),
): Flow<T> =
    callbackFlow {
        fun publish() {
            try {
                trySend(current())
            } catch (_: RuntimeException) {
                trySend(unknown)
            }
        }
        val unregister =
            try {
                register(::publish)
            } catch (_: RuntimeException) {
                trySend(unknown)
                null
            }
        if (unregister != null) publish()
        awaitClose { unregister?.invoke() }
    }

internal class AndroidPlatformStatusSource(
    context: Context,
    private val registrationObserver: PlatformRegistrationObserver = NoOpPlatformRegistrationObserver,
) : PlatformStatusSource {
    private val applicationContext = context.applicationContext
    private val connectivityManager = applicationContext.getSystemService(ConnectivityManager::class.java)
    private val powerManager = applicationContext.getSystemService(PowerManager::class.java)

    override fun notificationAccess(): NotificationAccessState =
        try {
            if (
                NotificationAccessStatus.isGranted(
                    applicationContext,
                    ComponentName(applicationContext, NfaNotificationListenerService::class.java),
                )
            ) {
                NotificationAccessState.Granted
            } else {
                NotificationAccessState.Required
            }
        } catch (_: RuntimeException) {
            NotificationAccessState.Unknown
        }

    override fun batteryOptimization(): BatteryOptimizationState =
        try {
            if (powerManager.isIgnoringBatteryOptimizations(applicationContext.packageName)) {
                BatteryOptimizationState.Exempt
            } else {
                BatteryOptimizationState.Optimized
            }
        } catch (_: RuntimeException) {
            BatteryOptimizationState.Unknown
        }

    override fun connectivityChanges(): Flow<ConnectivityState> =
        registeredStatusFlow(::connectivityState, ConnectivityState.Unknown) { publish ->
            val callback =
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) = publish()

                    override fun onLost(network: Network) = publish()

                    override fun onCapabilitiesChanged(
                        network: Network,
                        networkCapabilities: NetworkCapabilities,
                    ) = publish()
                }
            connectivityManager.registerDefaultNetworkCallback(callback)
            registrationObserver.onRegistered(PlatformRegistrationKind.Connectivity)
            return@registeredStatusFlow {
                try {
                    connectivityManager.unregisterNetworkCallback(callback)
                } finally {
                    registrationObserver.onUnregistered(PlatformRegistrationKind.Connectivity)
                }
            }
        }

    override fun batteryChanges(): Flow<BatteryOptimizationState> =
        registeredStatusFlow(::batteryOptimization, BatteryOptimizationState.Unknown) { publish ->
            val receiver =
                object : BroadcastReceiver() {
                    override fun onReceive(
                        context: Context?,
                        intent: Intent?,
                    ) {
                        publish()
                    }
                }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                applicationContext.registerReceiver(
                    receiver,
                    IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
                    Context.RECEIVER_NOT_EXPORTED,
                )
            } else {
                @Suppress("DEPRECATION")
                applicationContext.registerReceiver(
                    receiver,
                    IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
                )
            }
            registrationObserver.onRegistered(PlatformRegistrationKind.Battery)
            return@registeredStatusFlow {
                try {
                    applicationContext.unregisterReceiver(receiver)
                } finally {
                    registrationObserver.onUnregistered(PlatformRegistrationKind.Battery)
                }
            }
        }

    private fun connectivityState(): ConnectivityState =
        try {
            val active = connectivityManager.activeNetwork
            val capabilities = connectivityManager.getNetworkCapabilities(active)
            connectivityState(
                hasActiveNetwork = active != null,
                hasValidatedCapability =
                    capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
            )
        } catch (_: RuntimeException) {
            ConnectivityState.Unknown
        }
}
