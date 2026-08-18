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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

fun interface UniqueWorkEnqueuer {
    fun enqueue(
        name: String,
        policy: ExistingWorkPolicy,
        request: OneTimeWorkRequest,
    )
}

/**
 * Maintains one unique drain. External callers may replace an obsolete delayed request with earlier
 * work. While a worker is active, requested due times are recorded and appended after the drain so
 * a worker cannot cancel itself.
 */
class DeliveryWorkScheduler(
    private val enqueuer: UniqueWorkEnqueuer,
    private val clock: () -> Long = System::currentTimeMillis,
) : DeliveryScheduler {
    private val scheduleLock = Any()
    private var scheduledDueAtEpochMillis: Long = Long.MAX_VALUE
    private var runningWorkers = 0
    private var pendingWorkerDueAtEpochMillis: Long = Long.MAX_VALUE

    constructor(context: Context) : this(
        enqueuer =
            UniqueWorkEnqueuer { name, policy, request ->
                WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(name, policy, request)
            },
    )

    override fun ensureScheduled(dueAtEpochMillis: Long) {
        synchronized(scheduleLock) {
            if (runningWorkers > 0) {
                pendingWorkerDueAtEpochMillis = minOf(pendingWorkerDueAtEpochMillis, dueAtEpochMillis)
                return
            }
            if (dueAtEpochMillis >= scheduledDueAtEpochMillis) return
            enqueuer.enqueue(UNIQUE_WORK_NAME, ExistingWorkPolicy.REPLACE, request(dueAtEpochMillis))
            scheduledDueAtEpochMillis = dueAtEpochMillis
        }
    }

    fun onWorkerStarted() {
        synchronized(scheduleLock) {
            runningWorkers++
            scheduledDueAtEpochMillis = Long.MAX_VALUE
        }
    }

    fun onWorkerFinished(nextDueAtEpochMillis: Long?) {
        synchronized(scheduleLock) {
            if (runningWorkers == 0) return
            runningWorkers--
            if (runningWorkers > 0) {
                nextDueAtEpochMillis?.let { pendingWorkerDueAtEpochMillis = minOf(pendingWorkerDueAtEpochMillis, it) }
                return
            }
            val dueAt = minOf(pendingWorkerDueAtEpochMillis, nextDueAtEpochMillis ?: Long.MAX_VALUE)
            pendingWorkerDueAtEpochMillis = Long.MAX_VALUE
            if (dueAt == Long.MAX_VALUE) return
            enqueuer.enqueue(UNIQUE_WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request(dueAt))
            scheduledDueAtEpochMillis = dueAt
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
        return try {
            container.recoverExpiredSending()
            container.deliveryCoordinator.drainAvailable("worker-$id")
            container.runDeliveryMaintenance()
            Result.success()
        } finally {
            withContext(NonCancellable) {
                val nextDueAt = runCatching { container.nextDeliveryDueAt() }.getOrNull()
                container.deliveryScheduler.onWorkerFinished(nextDueAt)
            }
        }
    }
}
