package com.nfaalerts.collector.ui

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
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

    fun refresh() {
        notificationAccess.value = source.notificationAccess()
        batteryRefresh.value = source.batteryOptimization()
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
        publish()
        val unregister =
            try {
                register(::publish)
            } catch (_: RuntimeException) {
                trySend(unknown)
                null
            }
        awaitClose { unregister?.invoke() }
    }

internal class AndroidPlatformStatusSource(
    context: Context,
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
            connectivityManager.registerNetworkCallback(
                NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),
                callback,
            )
            return@registeredStatusFlow {
                runCatching { connectivityManager.unregisterNetworkCallback(callback) }
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
            return@registeredStatusFlow {
                runCatching { applicationContext.unregisterReceiver(receiver) }
            }
        }

    private fun connectivityState(): ConnectivityState =
        try {
            val active = connectivityManager.activeNetwork ?: return ConnectivityState.Disconnected
            val capabilities = connectivityManager.getNetworkCapabilities(active)
            if (capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true) {
                ConnectivityState.Connected
            } else {
                ConnectivityState.Disconnected
            }
        } catch (_: RuntimeException) {
            ConnectivityState.Unknown
        }
}
