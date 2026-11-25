package com.nfaalerts.collector.capture

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Android allows one app at most 50 active notifications. A selected source that
 * posts one notification per alert (BNN does) fills that budget and then the OS
 * silently rejects every new post before any listener can see it.
 *
 * The planner is pure so the dismissal rules stay testable: only selected
 * packages are considered, the newest N are always kept, group summaries are
 * ignored, and nothing leaves the shade until its capture is durable in Room.
 */
data class ShadePolicy(
    val keepNewestPerPackage: Int = DEFAULT_KEEP_NEWEST,
    val capThreshold: Int = CAP_THRESHOLD,
    val alertThreshold: Int = ALERT_THRESHOLD,
) {
    companion object {
        const val DEFAULT_KEEP_NEWEST = 10
        const val CAP_THRESHOLD = 40
        const val ALERT_THRESHOLD = 45
    }
}

data class ShadeNotification(
    val packageName: String,
    val key: String,
    val postTimeEpochMillis: Long,
    val isGroupSummary: Boolean = false,
    val isClearable: Boolean = true,
) {
    val identity: String
        get() = identityOf(key, postTimeEpochMillis)

    companion object {
        fun identityOf(
            key: String,
            postTimeEpochMillis: Long,
        ): String = "$key@$postTimeEpochMillis"
    }
}

enum class ShadeCaptureState {
    SENT,
    PERSISTED,
    NOT_PERSISTED,
}

data class ShadeDismissal(
    val packageName: String,
    val key: String,
    val state: ShadeCaptureState,
)

data class ShadePackagePlan(
    val packageName: String,
    val activeCount: Int,
    val dismissals: List<ShadeDismissal>,
    val capGuardTriggered: Boolean,
) {
    val remainingCount: Int
        get() = (activeCount - dismissals.size).coerceAtLeast(0)
}

data class ShadeSweepResult(
    val plans: List<ShadePackagePlan>,
    val observedCounts: Map<String, Int>,
    val alertPackages: List<String>,
) {
    val dismissedCount: Int
        get() = plans.sumOf { it.dismissals.size }

    val capGuardPackages: List<String>
        get() = plans.filter(ShadePackagePlan::capGuardTriggered).map(ShadePackagePlan::packageName)

    val largestObservedCount: Int
        get() = observedCounts.values.maxOrNull() ?: 0

    companion object {
        val EMPTY = ShadeSweepResult(emptyList(), emptyMap(), emptyList())
    }
}

object ShadePlanner {
    fun plan(
        packageName: String,
        active: List<ShadeNotification>,
        states: Map<String, ShadeCaptureState>,
        policy: ShadePolicy,
    ): ShadePackagePlan {
        val ordered =
            active
                .filterNot(ShadeNotification::isGroupSummary)
                .sortedWith(compareBy({ it.postTimeEpochMillis }, { it.key }))
        val keep = policy.keepNewestPerPackage.coerceAtLeast(0)
        val protectedIdentities = ordered.takeLast(keep).mapTo(mutableSetOf(), ShadeNotification::identity)
        val oldestFirst = ordered.filterNot { it.identity in protectedIdentities }

        val dismissedIdentities = mutableSetOf<String>()
        val dismissals = mutableListOf<ShadeDismissal>()

        fun dismiss(
            notification: ShadeNotification,
            state: ShadeCaptureState,
        ) {
            if (!notification.isClearable) return
            if (!dismissedIdentities.add(notification.identity)) return
            dismissals += ShadeDismissal(packageName, notification.key, state)
        }

        // Routine hygiene: a delivered (SENT) notification older than the newest
        // N is safe to remove. Persisted-but-unsent captures stay visible here.
        oldestFirst.forEach { notification ->
            val state = states[notification.identity] ?: ShadeCaptureState.NOT_PERSISTED
            if (state == ShadeCaptureState.SENT) dismiss(notification, state)
        }

        val capGuardTriggered = ordered.size >= policy.capThreshold
        if (capGuardTriggered) {
            var remaining = ordered.size - dismissals.size
            for (notification in oldestFirst) {
                if (remaining <= keep) break
                if (notification.identity in dismissedIdentities) continue
                val state = states[notification.identity] ?: ShadeCaptureState.NOT_PERSISTED
                if (state == ShadeCaptureState.SENT || state == ShadeCaptureState.PERSISTED) {
                    dismiss(notification, state)
                    remaining -= 1
                }
            }
        }

        return ShadePackagePlan(packageName, ordered.size, dismissals, capGuardTriggered)
    }
}

fun interface ShadeCaptureLookup {
    suspend fun statesFor(
        packageName: String,
        notifications: List<ShadeNotification>,
    ): Map<String, ShadeCaptureState>
}

fun interface ShadeDismisser {
    fun dismiss(key: String): Boolean
}

sealed interface ShadeEvent {
    data class Dismissed(
        val packageName: String,
        val count: Int,
        val sentCount: Int,
        val persistedCount: Int,
    ) : ShadeEvent

    data class CapGuard(
        val packageName: String,
        val beforeCount: Int,
        val afterCount: Int,
    ) : ShadeEvent

    data class Alert(
        val packageName: String,
        val count: Int,
    ) : ShadeEvent

    data class Swept(
        val result: ShadeSweepResult,
    ) : ShadeEvent
}

class ShadeHygiene(
    private val lookup: ShadeCaptureLookup,
    private val dismisser: ShadeDismisser,
    private val policyProvider: () -> ShadePolicy = { ShadePolicy() },
    private val onEvent: suspend (ShadeEvent) -> Unit = {},
    private val recount: (suspend () -> List<ShadeNotification>)? = null,
) {
    private val mutex = Mutex()

    suspend fun sweep(
        selectedPackages: Set<String>,
        active: List<ShadeNotification>,
    ): ShadeSweepResult {
        if (selectedPackages.isEmpty()) return ShadeSweepResult.EMPTY
        return mutex.withLock {
            val policy = policyProvider()
            val plans = plan(selectedPackages, active, policy)
            plans.forEach { plan ->
                plan.dismissals.forEach { dismisser.dismiss(it.key) }
                if (plan.dismissals.isNotEmpty()) {
                    onEvent(
                        ShadeEvent.Dismissed(
                            packageName = plan.packageName,
                            count = plan.dismissals.size,
                            sentCount = plan.dismissals.count { it.state == ShadeCaptureState.SENT },
                            persistedCount = plan.dismissals.count { it.state == ShadeCaptureState.PERSISTED },
                        ),
                    )
                }
                if (plan.capGuardTriggered) {
                    onEvent(ShadeEvent.CapGuard(plan.packageName, plan.activeCount, plan.remainingCount))
                }
            }
            val observedCounts = observedCounts(selectedPackages, plans)
            val alerts =
                plans
                    .filter(ShadePackagePlan::capGuardTriggered)
                    .mapNotNull { plan ->
                        val count = observedCounts[plan.packageName] ?: 0
                        if (count >= policy.alertThreshold) plan.packageName to count else null
                    }
            alerts.forEach { (packageName, count) -> onEvent(ShadeEvent.Alert(packageName, count)) }
            val result =
                ShadeSweepResult(
                    plans = plans,
                    observedCounts = observedCounts,
                    alertPackages = alerts.map { it.first },
                )
            onEvent(ShadeEvent.Swept(result))
            result
        }
    }

    private suspend fun plan(
        selectedPackages: Set<String>,
        active: List<ShadeNotification>,
        policy: ShadePolicy,
    ): List<ShadePackagePlan> =
        active
            .filter { it.packageName in selectedPackages && !it.isGroupSummary }
            .groupBy(ShadeNotification::packageName)
            .map { (packageName, notifications) ->
                val states = lookup.statesFor(packageName, notifications)
                ShadePlanner.plan(packageName, notifications, states, policy)
            }

    private suspend fun observedCounts(
        selectedPackages: Set<String>,
        plans: List<ShadePackagePlan>,
    ): Map<String, Int> {
        if (plans.none(ShadePackagePlan::capGuardTriggered)) {
            return plans.associate { it.packageName to it.remainingCount }
        }
        val fresh = recount?.invoke()
        if (fresh == null) {
            return plans.associate { it.packageName to it.remainingCount }
        }
        val counts =
            fresh
                .filter { it.packageName in selectedPackages && !it.isGroupSummary }
                .groupingBy(ShadeNotification::packageName)
                .eachCount()
        return plans.associate { it.packageName to (counts[it.packageName] ?: 0) }
    }
}
