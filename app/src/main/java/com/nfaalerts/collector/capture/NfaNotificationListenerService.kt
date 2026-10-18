@file:Suppress("DEPRECATION")

package com.nfaalerts.collector.capture

import android.app.Notification
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.nfaalerts.collector.CollectorReliabilityService
import com.nfaalerts.collector.NfaCollectorApp
import com.nfaalerts.collector.requestCollectorListenerRebind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class NfaNotificationListenerService : NotificationListenerService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var periodicReconcileJob: Job? = null

    override fun onListenerConnected() {
        super.onListenerConnected()
        appContainer().noteListenerConnected()
        appContainer().registerActiveListener(this)
        CollectorReliabilityService.update(this)
        reconcileActiveNotifications()
        startDegradedShadeLoop()
    }

    override fun onListenerDisconnected() {
        periodicReconcileJob?.cancel()
        periodicReconcileJob = null
        appContainer().unregisterActiveListener(this)
        appContainer().noteListenerDisconnected()
        requestCollectorListenerRebind(this)
        super.onListenerDisconnected()
    }

    private fun startDegradedShadeLoop() {
        periodicReconcileJob?.cancel()
        periodicReconcileJob =
            serviceScope.launch {
                while (isActive) {
                    if (!appContainer().shadeCatchUpActive()) {
                        delay(HEALTHY_PACE_POLL_MS)
                        continue
                    }
                    delay(SHADE_RECONCILE_INTERVAL_MS)
                    if (appContainer().shadeCatchUpActive()) {
                        reconcileActiveNotifications()
                    }
                }
            }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        try {
            if (isSystemAutogroupSummary(sbn)) return
            // Clone before leaving the binder thread so async persist cannot lose extras.
            appContainer().postedNotificationCallback.onNotificationPosted(toLightweight(sbn))
        } catch (_: Throwable) {
            appContainer().listenerStatus.onPostedCallbackFailure()
        }
    }

    internal fun reconcileActiveNotifications() {
        val active =
            runCatching { activeNotifications?.toList().orEmpty() }
                .getOrDefault(emptyList())
                .filterNot(::isSystemAutogroupSummary)
                .map { toLightweight(it).copy(capturePath = CapturePath.SHADE) }
        serviceScope.launch {
            appContainer().reconcileActiveNotifications(active)
        }
    }

    private fun toLightweight(sbn: StatusBarNotification): LightweightPostedNotification {
        val clonedNotification =
            runCatching { sbn.notification.clone() as Notification }
                .getOrElse { sbn.notification }
        return LightweightPostedNotification(
            packageName = sbn.packageName,
            key = sbn.key,
            notificationId = sbn.id,
            tag = sbn.tag,
            postTimeEpochMillis = sbn.postTime,
            uid =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    sbn.uid
                } else {
                    UID_UNAVAILABLE
                },
            userId = sbn.userId,
            isOngoing = sbn.isOngoing,
            isClearable = sbn.isClearable,
            groupKey = sbn.groupKey,
            overrideGroupKey = sbn.overrideGroupKey,
            notificationHandle = clonedNotification,
        )
    }

    private fun isSystemAutogroupSummary(sbn: StatusBarNotification): Boolean =
        isSystemAutogroupSummary(
            sbn.notification.flags,
            Notification.FLAG_GROUP_SUMMARY,
            Notification.FLAG_LOCAL_ONLY,
        )

    private fun appContainer() = (application as NfaCollectorApp).appContainer

    private companion object {
        const val UID_UNAVAILABLE = -1
        const val HEALTHY_PACE_POLL_MS = 1_000L
        const val SHADE_RECONCILE_INTERVAL_MS = ShadeReconcilePace.DEGRADED_INTERVAL_MS
    }
}
