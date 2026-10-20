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
        handlePosted(sbn, ranking = null)
    }

    override fun onNotificationPosted(
        sbn: StatusBarNotification,
        rankingMap: RankingMap,
    ) {
        handlePosted(sbn, readRanking(sbn.key, rankingMap))
    }

    internal fun reconcileActiveNotifications() {
        val rankingMap = runCatching { currentRanking }.getOrNull()
        val active =
            runCatching { activeNotifications?.toList().orEmpty() }
                .getOrDefault(emptyList())
                .filterNot(::isSystemAutogroupSummary)
                .map { sbn ->
                    toLightweight(sbn, rankingMap?.let { readRanking(sbn.key, it) })
                        .copy(capturePath = CapturePath.SHADE)
                }
        serviceScope.launch {
            appContainer().reconcileActiveNotifications(active)
        }
    }

    private fun handlePosted(
        sbn: StatusBarNotification,
        ranking: RankingSnapshot?,
    ) {
        try {
            appContainer().awaitSelectionsReady()
            if (isSystemAutogroupSummary(sbn)) {
                appContainer().noteCaptureDrop(
                    CaptureDropReason.AUTOGROUP_SUMMARY,
                    sbn.packageName,
                    CapturePath.LIVE,
                )
                return
            }
            appContainer().postedNotificationCallback.onNotificationPosted(toLightweight(sbn, ranking))
        } catch (_: Throwable) {
            appContainer().listenerStatus.onPostedCallbackFailure()
        }
    }

    private fun readRanking(
        key: String,
        rankingMap: RankingMap,
    ): RankingSnapshot? {
        val ranking = Ranking()
        if (!rankingMap.getRanking(key, ranking)) return null
        val channel = runCatching { ranking.channel }.getOrNull()
        return RankingSnapshot(
            importance = ranking.importance,
            suppressedVisualEffects = ranking.suppressedVisualEffects,
            matchesInterruptionFilter = ranking.matchesInterruptionFilter(),
            ambient = ranking.isAmbient,
            lastAudibleAlertAtEpochMillis = ranking.lastAudiblyAlertedMillis,
            channelId = channel?.id,
            lockscreenVisibilityOverride = ranking.lockscreenVisibilityOverride,
            channelLockscreenVisibility = channel?.lockscreenVisibility,
            suspended = ranking.isSuspended,
        )
    }

    private fun toLightweight(
        sbn: StatusBarNotification,
        ranking: RankingSnapshot?,
    ): LightweightPostedNotification {
        val cloneOutcome = runCatching { sbn.notification.clone() }
        val notification =
            notificationAfterCopy(
                cloneSucceeded = cloneOutcome.isSuccess,
                cloned = cloneOutcome.getOrNull(),
                original = sbn.notification,
                onCloneFailure = {
                    appContainer().noteCaptureDrop(
                        CaptureDropReason.COPY_CLONE_FAILURE,
                        sbn.packageName,
                        CapturePath.LIVE,
                    )
                },
            )
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
            notificationHandle = notification,
            ranking = ranking,
        )
    }

    private fun isSystemAutogroupSummary(sbn: StatusBarNotification): Boolean =
        autogroupAdmission(
            sbn.notification.flags,
            Notification.FLAG_GROUP_SUMMARY,
            Notification.FLAG_LOCAL_ONLY,
        ) != null

    private fun appContainer() = (application as NfaCollectorApp).appContainer

    private companion object {
        const val UID_UNAVAILABLE = -1
        const val HEALTHY_PACE_POLL_MS = 1_000L
        const val SHADE_RECONCILE_INTERVAL_MS = ShadeReconcilePace.DEGRADED_INTERVAL_MS
    }
}
