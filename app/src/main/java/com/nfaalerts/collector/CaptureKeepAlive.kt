package com.nfaalerts.collector

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Network-free periodic wake that only protects capture bind/FGS. Delivery remains on its own
 * network-constrained drain worker.
 */
object CaptureKeepAliveScheduler {
    const val UNIQUE_WORK_NAME = "nfa-capture-keepalive"

    fun ensure(context: Context) {
        val request =
            PeriodicWorkRequestBuilder<CaptureKeepAliveWorker>(15, TimeUnit.MINUTES)
                .build()
        WorkManager
            .getInstance(context.applicationContext)
            .enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
    }
}

class CaptureKeepAliveWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        if (!hasNotificationAccess(applicationContext)) return Result.success()
        CollectorReliabilityService.update(applicationContext)
        val connected =
            (applicationContext as? NfaCollectorApp)
                ?.appContainer
                ?.listenerStatus
                ?.state
                ?.value
                ?.connected == true
        val app = applicationContext as? NfaCollectorApp
        if (!connected) {
            requestCollectorListenerRebind(applicationContext)
        }
        app?.appContainer?.requestConnectedShadeReconcile()
        return Result.success()
    }
}
