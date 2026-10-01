package com.nfaalerts.collector.capture

import android.app.Notification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking

class ActiveNotificationCatchUpTest {
    @Test
    fun `reconcile imports only missing selected identities`() =
        runBlocking {
            val seen = mutableSetOf<String>()
            val imported = mutableListOf<String>()
            val catchUp =
                ActiveNotificationCatchUp(
                    alreadyCaptured = { packageName, key, postTime ->
                        "$packageName|$key|$postTime" in seen
                    },
                    onMissing = { input ->
                        if (input.packageName != "us.bnn.newsapp") return@ActiveNotificationCatchUp false
                        imported += input.key
                        true
                    },
                )
            seen += "us.bnn.newsapp|key-a|100"
            val result =
                catchUp.reconcile(
                    listOf(
                        light("us.bnn.newsapp", "key-a", 100),
                        light("us.bnn.newsapp", "key-b", 200),
                        light("com.other", "key-c", 300),
                    ),
                )
            assertEquals(3, result.examined)
            assertEquals(1, result.imported)
            assertEquals(1, result.skippedExisting)
            assertEquals(1, result.skippedUnselected)
            assertEquals(listOf("key-b"), imported)
        }

    @Test
    fun `system autogroup summary requires both group summary and local only flags`() {
        assertTrue(
            isSystemAutogroupSummary(
                Notification.FLAG_GROUP_SUMMARY or Notification.FLAG_LOCAL_ONLY,
                Notification.FLAG_GROUP_SUMMARY,
                Notification.FLAG_LOCAL_ONLY,
            ),
        )
        assertFalse(
            isSystemAutogroupSummary(
                Notification.FLAG_GROUP_SUMMARY,
                Notification.FLAG_GROUP_SUMMARY,
                Notification.FLAG_LOCAL_ONLY,
            ),
        )
        assertFalse(
            isSystemAutogroupSummary(
                0,
                Notification.FLAG_GROUP_SUMMARY,
                Notification.FLAG_LOCAL_ONLY,
            ),
        )
    }

    private fun light(
        packageName: String,
        key: String,
        postTime: Long,
    ) = LightweightPostedNotification(
        packageName = packageName,
        key = key,
        notificationId = 0,
        tag = key,
        postTimeEpochMillis = postTime,
        uid = 1,
        userId = 0,
        isOngoing = false,
        isClearable = true,
        notificationHandle = Any(),
    )
}
