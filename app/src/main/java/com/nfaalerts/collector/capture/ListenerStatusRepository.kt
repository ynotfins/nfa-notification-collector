package com.nfaalerts.collector.capture

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class ListenerStatus(
    val connected: Boolean = false,
    val lastConnectedAtEpochMillis: Long? = null,
    val lastDisconnectedAtEpochMillis: Long? = null,
    val dispatchedCount: Long = 0,
    val dispatchRejectedCount: Long = 0,
    val workerFailureCount: Long = 0,
    val lastWorkerFailureType: String? = null,
    val activeWorkers: Int = 0,
    val maximumObservedBacklog: Int = 0,
)

interface CaptureDispatchDiagnostics {
    fun onDispatchStarted()

    fun onDispatchFinished()

    fun onDispatchRejected()

    fun onWorkerFailure(failureType: String)
}

class ListenerStatusRepository(
    private val clock: () -> Long = System::currentTimeMillis,
) : CaptureDispatchDiagnostics {
    private val mutableState = MutableStateFlow(ListenerStatus())
    val state: StateFlow<ListenerStatus> = mutableState.asStateFlow()

    fun onListenerConnected() {
        mutableState.update { it.copy(connected = true, lastConnectedAtEpochMillis = clock()) }
    }

    fun onListenerDisconnected() {
        mutableState.update { it.copy(connected = false, lastDisconnectedAtEpochMillis = clock()) }
    }

    override fun onDispatchStarted() {
        mutableState.update {
            val active = if (it.activeWorkers == Int.MAX_VALUE) Int.MAX_VALUE else it.activeWorkers + 1
            it.copy(
                dispatchedCount = it.dispatchedCount + 1,
                activeWorkers = active,
                maximumObservedBacklog = maxOf(it.maximumObservedBacklog, active),
            )
        }
    }

    override fun onDispatchFinished() {
        mutableState.update { it.copy(activeWorkers = maxOf(0, it.activeWorkers - 1)) }
    }

    override fun onDispatchRejected() {
        mutableState.update { it.copy(dispatchRejectedCount = it.dispatchRejectedCount + 1) }
    }

    override fun onWorkerFailure(failureType: String) {
        mutableState.update {
            it.copy(
                workerFailureCount = it.workerFailureCount + 1,
                lastWorkerFailureType = failureType,
            )
        }
    }
}

object NotificationAccessStatus {
    fun isGranted(
        context: Context,
        component: ComponentName,
    ): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            context
                .getSystemService(NotificationManager::class.java)
                .isNotificationListenerAccessGranted(component)
        } else {
            Settings.Secure
                .getString(context.contentResolver, "enabled_notification_listeners")
                ?.split(':')
                ?.mapNotNull(ComponentName::unflattenFromString)
                ?.contains(component) == true
        }
}
