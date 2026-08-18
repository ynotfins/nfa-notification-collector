package com.nfaalerts.collector.delivery

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.nfaalerts.collector.NfaCollectorApp
import java.util.concurrent.TimeUnit

fun interface UniqueWorkEnqueuer {
    fun enqueue(
        name: String,
        policy: ExistingWorkPolicy,
        request: OneTimeWorkRequest,
    )
}

/**
 * Maintains one replaceable unique drain. REPLACE preempts an obsolete delayed request when earlier
 * database work appears. The in-process earliest-due guard prevents a cancelled active worker's
 * later lease wake from replacing that earlier request; Room lease recovery is the durable fallback
 * after process death.
 */
class DeliveryWorkScheduler(
    private val enqueuer: UniqueWorkEnqueuer,
    private val clock: () -> Long = System::currentTimeMillis,
) : DeliveryScheduler {
    private val scheduleLock = Any()
    private var scheduledDueAtEpochMillis: Long = Long.MAX_VALUE

    constructor(context: Context) : this(
        enqueuer =
            UniqueWorkEnqueuer { name, policy, request ->
                WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(name, policy, request)
            },
    )

    override fun ensureScheduled(dueAtEpochMillis: Long) {
        synchronized(scheduleLock) {
            if (dueAtEpochMillis >= scheduledDueAtEpochMillis) return
            enqueuer.enqueue(UNIQUE_WORK_NAME, ExistingWorkPolicy.REPLACE, request(dueAtEpochMillis))
            scheduledDueAtEpochMillis = dueAtEpochMillis
        }
    }

    fun onWorkerStarted() {
        synchronized(scheduleLock) {
            scheduledDueAtEpochMillis = Long.MAX_VALUE
        }
    }

    private fun request(dueAtEpochMillis: Long): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<DeliveryDrainWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInitialDelay((dueAtEpochMillis - clock()).coerceAtLeast(0L), TimeUnit.MILLISECONDS)
            .build()

    companion object {
        const val UNIQUE_WORK_NAME = "nfa-delivery-drain"
    }
}

class DeliveryDrainWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? NfaCollectorApp ?: return Result.failure()
        val container = app.appContainer
        container.deliveryScheduler.onWorkerStarted()
        container.recoverExpiredSending()
        container.deliveryCoordinator.drainAvailable("worker-$id")
        container.runDeliveryMaintenance()
        container.nextDeliveryDueAt()?.let(container.deliveryScheduler::ensureScheduled)
        return Result.success()
    }
}
