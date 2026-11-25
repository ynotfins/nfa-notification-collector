package com.nfaalerts.collector.capture

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nfaalerts.collector.AppContainer
import com.nfaalerts.collector.NfaCollectorApp
import com.nfaalerts.collector.config.SelectionUpdate
import com.nfaalerts.collector.config.SourceSelection
import com.nfaalerts.collector.hasNotificationAccess
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class ShadeHygieneInstrumentedTest {
    @Test
    fun sixtySelectedPostsStayUnderTheOsCapAndAllLandInRoom() {
        val app = ApplicationProvider.getApplicationContext<NfaCollectorApp>()
        val targetContext = app.applicationContext
        assumeTrue("notification access is an operator-only grant", hasNotificationAccess(targetContext))
        val container = app.appContainer
        assumeTrue("listener must be connected", waitForListener(container))

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val testPackage = targetContext.packageName
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching {
                instrumentation.uiAutomation.grantRuntimePermission(
                    testPackage,
                    Manifest.permission.POST_NOTIFICATIONS,
                )
            }
            assumeTrue(
                "POST_NOTIFICATIONS must be granted to the app under test",
                waitForPostPermission(targetContext),
            )
        }
        val manager = targetContext.getSystemService(NotificationManager::class.java)
        assumeTrue("notification manager is required", manager != null)
        assumeTrue("app must be allowed to post", manager!!.areNotificationsEnabled())

        val tagPrefix = "shade-test-${System.nanoTime()}-"
        val originalConfig = runBlocking { container.configStore.exportPayload() }
        val maxObserved = AtomicInteger()
        try {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Shade hygiene test", NotificationManager.IMPORTANCE_DEFAULT),
            )
            val added =
                runBlocking {
                    container.sourceSelections.upsert(
                        SourceSelection(
                            packageName = testPackage,
                            appLabel = "Shade hygiene test",
                            sourceId = "local",
                            enabled = true,
                            bnnMappingConfirmed = false,
                            rawTextOrder = RawTextField.DEFAULT_ORDER,
                        ),
                    )
                }
            assertEquals(SelectionUpdate.Accepted, added)

            repeat(TOTAL_POSTS) { index ->
                manager.notify(tagPrefix + index, index, notification(targetContext))
                val active = activeCount(container, testPackage, tagPrefix)
                maxObserved.updateAndGet { maxOf(it, active) }
                Thread.sleep(POST_INTERVAL_MS)
            }

            val captured = waitForCaptured(container, testPackage, tagPrefix, TOTAL_POSTS)
            val finalCount = activeCount(container, testPackage, tagPrefix)

            assertEquals("every selected post must reach Room", TOTAL_POSTS, captured)
            assertTrue(
                "shade count exceeded the cap guard: max=$maxObserved",
                maxObserved.get() <= ShadePolicy.CAP_THRESHOLD,
            )
            assertTrue(
                "shade count did not settle under the cap: final=$finalCount",
                finalCount <= ShadePolicy.CAP_THRESHOLD,
            )
        } finally {
            repeat(TOTAL_POSTS) { index -> manager.cancel(tagPrefix + index, index) }
            runBlocking { container.deleteCapturedByTagPrefix(testPackage, tagPrefix) }
            runBlocking { container.configStore.savePayload(originalConfig) }
            runBlocking { container.sourceSelections.load() }
            originalConfig.fill(0)
        }
    }

    private fun activeCount(
        container: AppContainer,
        packageName: String,
        tagPrefix: String,
    ): Int =
        container
            .activeShadeNotifications()
            .count { it.packageName == packageName && !it.isGroupSummary && it.key.contains(tagPrefix) }

    private fun waitForCaptured(
        container: AppContainer,
        packageName: String,
        tagPrefix: String,
        expected: Int,
    ): Int {
        val deadline = System.currentTimeMillis() + CAPTURE_TIMEOUT_MS
        var captured = 0
        while (System.currentTimeMillis() < deadline) {
            captured = runBlocking { container.capturedCountByTagPrefix(packageName, tagPrefix) }
            if (captured >= expected) return captured
            Thread.sleep(POLL_INTERVAL_MS)
        }
        return captured
    }

    private fun waitForListener(container: AppContainer): Boolean {
        val deadline = System.currentTimeMillis() + LISTENER_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (container.listenerStatus.state.value.connected) return true
            Thread.sleep(POLL_INTERVAL_MS)
        }
        return container.listenerStatus.state.value.connected
    }

    private fun waitForPostPermission(context: Context): Boolean {
        val deadline = System.currentTimeMillis() + PERMISSION_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                return true
            }
            Thread.sleep(POLL_INTERVAL_MS)
        }
        return false
    }

    private fun notification(context: Context): Notification =
        Notification
            .Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Shade hygiene test")
            .setContentText("Synthetic selected-source post")
            .setAutoCancel(true)
            .build()

    private companion object {
        const val CHANNEL_ID = "shade-hygiene-test"
        const val TOTAL_POSTS = 60
        const val POST_INTERVAL_MS = 100L
        const val POLL_INTERVAL_MS = 100L
        const val LISTENER_TIMEOUT_MS = 10_000L
        const val PERMISSION_TIMEOUT_MS = 3_000L
        const val CAPTURE_TIMEOUT_MS = 15_000L
    }
}
