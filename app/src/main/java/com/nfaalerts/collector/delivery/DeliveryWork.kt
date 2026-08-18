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

class DeliveryWorkScheduler(
    private val enqueuer: UniqueWorkEnqueuer,
    private val clock: () -> Long = System::currentTimeMillis,
) : DeliveryScheduler {
    constructor(context: Context) : this(
        enqueuer =
            UniqueWorkEnqueuer { name, policy, request ->
                WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(name, policy, request)
            },
    )

    override fun ensureScheduled(dueAtEpochMillis: Long) {
        enqueuer.enqueue(UNIQUE_WORK_NAME, ExistingWorkPolicy.KEEP, request(dueAtEpochMillis))
    }

    fun enqueueFollowUp(dueAtEpochMillis: Long) {
        enqueuer.enqueue(UNIQUE_WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request(dueAtEpochMillis))
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
        container.deliveryCoordinator.drainAvailable("worker-$id")
        container.runDeliveryMaintenance()
        container.nextDeliveryDueAt()?.let(container.deliveryScheduler::enqueueFollowUp)
        return Result.success()
    }
}
