package com.nfaalerts.collector.capture

import com.nfaalerts.collector.config.AllowlistSnapshot
import com.nfaalerts.collector.config.SourceSelection

data class LightweightPostedNotification(
    val packageName: String,
    val key: String,
    val notificationId: Int,
    val tag: String?,
    val postTimeEpochMillis: Long,
    val uid: Int,
    val userId: Int,
    val isOngoing: Boolean,
    val isClearable: Boolean,
    val notificationHandle: Any,
)

data class NotificationIdentity(
    val packageName: String,
    val key: String,
    val notificationId: Int,
    val tag: String?,
    val postTimeEpochMillis: Long,
    val uid: Int,
    val userId: Int,
    val isOngoing: Boolean,
    val isClearable: Boolean,
)

data class DispatchedNotification(
    val eventId: String,
    val capturedAtEpochMillis: Long,
    val identity: NotificationIdentity,
    val source: SourceSelection,
    val notificationHandle: Any,
)

fun interface CaptureDispatcher {
    fun dispatch(request: DispatchedNotification)
}

class PostedNotificationCallback(
    private val allowlistProvider: () -> AllowlistSnapshot,
    private val eventIdFactory: () -> String,
    private val clock: () -> Long,
    private val dispatcher: CaptureDispatcher,
) {
    fun onNotificationPosted(input: LightweightPostedNotification): Boolean {
        val source = allowlistProvider().sourceFor(input.packageName) ?: return false
        dispatcher.dispatch(
            DispatchedNotification(
                eventId = eventIdFactory(),
                capturedAtEpochMillis = clock(),
                identity =
                    NotificationIdentity(
                        packageName = input.packageName,
                        key = input.key,
                        notificationId = input.notificationId,
                        tag = input.tag,
                        postTimeEpochMillis = input.postTimeEpochMillis,
                        uid = input.uid,
                        userId = input.userId,
                        isOngoing = input.isOngoing,
                        isClearable = input.isClearable,
                    ),
                source = source,
                notificationHandle = input.notificationHandle,
            ),
        )
        return true
    }
}
