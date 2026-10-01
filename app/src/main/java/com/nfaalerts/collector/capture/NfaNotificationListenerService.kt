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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class NfaNotificationListenerService : NotificationListenerService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onListenerConnected() {
        super.onListenerConnected()
        appContainer().listenerStatus.onListenerConnected()
        CollectorReliabilityService.update(this)
        reconcileActiveNotifications()
    }

    override fun onListenerDisconnected() {
        appContainer().listenerStatus.onListenerDisconnected()
        requestCollectorListenerRebind(this)
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (isSystemAutogroupSummary(sbn)) return
        appContainer().postedNotificationCallback.onNotificationPosted(toLightweight(sbn))
    }

    private fun reconcileActiveNotifications() {
        val active =
            runCatching { activeNotifications?.toList().orEmpty() }
                .getOrDefault(emptyList())
                .filterNot(::isSystemAutogroupSummary)
                .map(::toLightweight)
        serviceScope.launch {
            appContainer().reconcileActiveNotifications(active)
        }
    }

    private fun toLightweight(sbn: StatusBarNotification): LightweightPostedNotification =
        LightweightPostedNotification(
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
            notificationHandle = sbn.notification,
        )

    private fun isSystemAutogroupSummary(sbn: StatusBarNotification): Boolean =
        isSystemAutogroupSummary(
            sbn.notification.flags,
            Notification.FLAG_GROUP_SUMMARY,
            Notification.FLAG_LOCAL_ONLY,
        )

    private fun appContainer() = (application as NfaCollectorApp).appContainer

    private companion object {
        const val UID_UNAVAILABLE = -1
    }
}
