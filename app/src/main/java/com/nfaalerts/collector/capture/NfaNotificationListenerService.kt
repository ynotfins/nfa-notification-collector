@file:Suppress("DEPRECATION")

package com.nfaalerts.collector.capture

import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.nfaalerts.collector.NfaCollectorApp

class NfaNotificationListenerService : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        appContainer().listenerStatus.onListenerConnected()
    }

    override fun onListenerDisconnected() {
        appContainer().listenerStatus.onListenerDisconnected()
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val input =
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
        appContainer().postedNotificationCallback.onNotificationPosted(input)
    }

    private fun appContainer() = (application as NfaCollectorApp).appContainer

    private companion object {
        const val UID_UNAVAILABLE = -1
    }
}
