package com.nfaalerts.collector.capture

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShadeHygieneTest {
    private val policy = ShadePolicy(keepNewestPerPackage = 10)

    @Test
    fun `delivered notifications older than the newest N are dismissed`() {
        val active = (1..12).map { notification("us.bnn.newsapp", "key-$it", it.toLong()) }
        val states = active.associate { it.identity to ShadeCaptureState.SENT }

        val plan = ShadePlanner.plan("us.bnn.newsapp", active, states, policy)

        assertEquals(listOf("key-1", "key-2"), plan.dismissals.map(ShadeDismissal::key))
        assertEquals(10, plan.remainingCount)
        assertFalse(plan.capGuardTriggered)
    }

    @Test
    fun `persisted but unsent notifications are not dismissed below the cap guard`() {
        val active = (1..12).map { notification("us.bnn.newsapp", "key-$it", it.toLong()) }
        val states = active.associate { it.identity to ShadeCaptureState.PERSISTED }

        val plan = ShadePlanner.plan("us.bnn.newsapp", active, states, policy)

        assertTrue(plan.dismissals.isEmpty())
        assertFalse(plan.capGuardTriggered)
    }

    @Test
    fun `ordering uses post time and keeps the newest N`() {
        val active =
            (1..12)
                .map { index -> notification("us.bnn.newsapp", "key-$index", index * 1_000L) }
                .shuffled()
        val states = active.associate { it.identity to ShadeCaptureState.SENT }

        val plan = ShadePlanner.plan("us.bnn.newsapp", active, states, policy)

        assertEquals(listOf("key-1", "key-2"), plan.dismissals.map(ShadeDismissal::key))
        assertTrue(plan.dismissals.none { it.key in (3..12).map { index -> "key-$index" } })
    }

    @Test
    fun `cap guard triggers at 40 and stops at the newest N`() {
        val active = (1..40).map { notification("us.bnn.newsapp", "key-$it", it.toLong()) }
        val states = active.associate { it.identity to ShadeCaptureState.PERSISTED }

        val plan = ShadePlanner.plan("us.bnn.newsapp", active, states, policy)

        assertTrue(plan.capGuardTriggered)
        assertEquals(30, plan.dismissals.size)
        assertEquals(10, plan.remainingCount)
        assertTrue(plan.dismissals.none { it.key in (31..40).map { index -> "key-$index" } })
    }

    @Test
    fun `cap guard does not trigger below 40`() {
        val active = (1..39).map { notification("us.bnn.newsapp", "key-$it", it.toLong()) }
        val states = active.associate { it.identity to ShadeCaptureState.PERSISTED }

        val plan = ShadePlanner.plan("us.bnn.newsapp", active, states, policy)

        assertFalse(plan.capGuardTriggered)
        assertTrue(plan.dismissals.isEmpty())
    }

    @Test
    fun `group summaries are excluded from counts and never dismissed`() {
        val children = (1..50).map { notification("us.bnn.newsapp", "child-$it", it.toLong()) }
        val summaries = (1..5).map { notification("us.bnn.newsapp", "summary-$it", it.toLong(), isGroupSummary = true) }
        val states = children.associate { it.identity to ShadeCaptureState.SENT }

        val plan = ShadePlanner.plan("us.bnn.newsapp", children + summaries, states, policy)

        assertEquals(50, plan.activeCount)
        assertEquals(40, plan.dismissals.size)
        assertTrue(plan.dismissals.none { it.key.startsWith("summary-") })
    }

    @Test
    fun `notifications that are not in Room are never dismissed`() {
        val active = (1..50).map { notification("us.bnn.newsapp", "key-$it", it.toLong()) }

        val plan = ShadePlanner.plan("us.bnn.newsapp", active, emptyMap(), policy)

        assertTrue(plan.capGuardTriggered)
        assertTrue(plan.dismissals.isEmpty())
    }

    @Test
    fun `cap guard dismisses SENT first then persisted captures`() {
        val active = (1..45).map { notification("us.bnn.newsapp", "key-$it", it.toLong()) }
        val states =
            active.associate { notification ->
                notification.identity to
                    if (notification.postTimeEpochMillis <= 5) {
                        ShadeCaptureState.SENT
                    } else {
                        ShadeCaptureState.PERSISTED
                    }
            }

        val plan = ShadePlanner.plan("us.bnn.newsapp", active, states, policy)

        assertEquals(35, plan.dismissals.size)
        assertEquals(5, plan.dismissals.count { it.state == ShadeCaptureState.SENT })
        assertEquals(30, plan.dismissals.count { it.state == ShadeCaptureState.PERSISTED })
    }

    @Test
    fun `non-clearable notifications are never dismissed`() {
        val active = (1..50).map { notification("us.bnn.newsapp", "key-$it", it.toLong(), isClearable = false) }
        val states = active.associate { it.identity to ShadeCaptureState.SENT }

        val plan = ShadePlanner.plan("us.bnn.newsapp", active, states, policy)

        assertTrue(plan.dismissals.isEmpty())
    }

    @Test
    fun `non-selected packages are untouched`() {
        val selected = (1..12).map { notification("us.bnn.newsapp", "bnn-$it", it.toLong()) }
        val other = (1..50).map { notification("com.example.other", "other-$it", it.toLong()) }
        val states =
            (selected + other).associate { it.identity to ShadeCaptureState.SENT }

        val result = sweep(selectedPackages = setOf("us.bnn.newsapp"), active = selected + other, states = states)

        assertEquals(listOf("us.bnn.newsapp"), result.plans.map(ShadePackagePlan::packageName))
        assertEquals(2, result.dismissedCount)
        assertTrue(result.plans.none { it.packageName == "com.example.other" })
    }

    @Test
    fun `alert is raised only when the package is still at or above 45 after the guard`() {
        val active = (1..45).map { notification("us.bnn.newsapp", "key-$it", it.toLong()) }
        val states = active.associate { it.identity to ShadeCaptureState.PERSISTED }

        val failed =
            sweep(
                selectedPackages = setOf("us.bnn.newsapp"),
                active = active,
                states = states,
                recount = { active },
            )
        val succeeded =
            sweep(
                selectedPackages = setOf("us.bnn.newsapp"),
                active = active,
                states = states,
                recount = { (1..10).map { notification("us.bnn.newsapp", "key-$it", it.toLong()) } },
            )

        assertEquals(listOf("us.bnn.newsapp"), failed.alertPackages)
        assertEquals(45, failed.observedCounts.getValue("us.bnn.newsapp"))
        assertTrue(succeeded.alertPackages.isEmpty())
        assertEquals(10, succeeded.observedCounts.getValue("us.bnn.newsapp"))
    }

    @Test
    fun `controller dismisses only through the dismisser and reports aggregate counts`() {
        val active = (1..12).map { notification("us.bnn.newsapp", "key-$it", it.toLong()) }
        val states = active.associate { it.identity to ShadeCaptureState.SENT }
        val dismissed = mutableListOf<String>()
        val events = mutableListOf<ShadeEvent>()
        val lookup =
            ShadeCaptureLookup { _, notifications ->
                notifications.associate { it.identity to states.getValue(it.identity) }
            }
        val dismisser =
            ShadeDismisser { key ->
                dismissed += key
                true
            }

        val result =
            runBlocking {
                ShadeHygiene(
                    lookup = lookup,
                    dismisser = dismisser,
                    policyProvider = { policy },
                    onEvent = { events += it },
                ).sweep(setOf("us.bnn.newsapp"), active)
            }

        assertEquals(listOf("key-1", "key-2"), dismissed)
        assertEquals(2, result.dismissedCount)
        val dismissedEvent = events.filterIsInstance<ShadeEvent.Dismissed>().single()
        assertEquals(2, dismissedEvent.sentCount)
        assertEquals(0, dismissedEvent.persistedCount)
    }

    private fun sweep(
        selectedPackages: Set<String>,
        active: List<ShadeNotification>,
        states: Map<String, ShadeCaptureState>,
        recount: (suspend () -> List<ShadeNotification>)? = null,
    ): ShadeSweepResult {
        val lookup =
            ShadeCaptureLookup { _, notifications ->
                notifications.associate {
                    it.identity to (states[it.identity] ?: ShadeCaptureState.NOT_PERSISTED)
                }
            }
        return runBlocking {
            ShadeHygiene(
                lookup = lookup,
                dismisser = ShadeDismisser { true },
                policyProvider = { policy },
                recount = recount,
            ).sweep(selectedPackages, active)
        }
    }

    private fun notification(
        packageName: String,
        key: String,
        postTime: Long,
        isGroupSummary: Boolean = false,
        isClearable: Boolean = true,
    ) = ShadeNotification(
        packageName = packageName,
        key = key,
        postTimeEpochMillis = postTime,
        isGroupSummary = isGroupSummary,
        isClearable = isClearable,
    )
}
