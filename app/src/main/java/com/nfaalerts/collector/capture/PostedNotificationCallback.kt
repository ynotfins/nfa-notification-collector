package com.nfaalerts.collector.capture

import com.nfaalerts.collector.config.AllowlistSnapshot
import com.nfaalerts.collector.config.SelectionLoadState
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
    val groupKey: String? = null,
    val overrideGroupKey: String? = null,
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
    val groupKey: String? = null,
    val overrideGroupKey: String? = null,
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
    private val selectionLoadStateProvider: () -> SelectionLoadState,
    private val eventIdFactory: () -> String,
    private val clock: () -> Long,
    private val dispatcher: CaptureDispatcher,
    private val initializationDiagnostics: InitializationCaptureDiagnostics = NoOpInitializationCaptureDiagnostics,
    private val initializationBufferCapacity: Int = INITIALIZATION_BUFFER_CAPACITY,
) {
    private val initializationLock = Any()
    private val initializationBuffer = ArrayDeque<BufferedNotification>()
    private var initializationComplete = selectionLoadStateProvider() !is SelectionLoadState.Loading

    init {
        require(initializationBufferCapacity > 0) { "Initialization buffer capacity must be positive." }
    }

    fun onNotificationPosted(input: LightweightPostedNotification): Boolean {
        synchronized(initializationLock) {
            if (!initializationComplete) {
                if (initializationBuffer.size >= initializationBufferCapacity) {
                    initializationDiagnostics.onInitializationOverflow()
                    return false
                }
                initializationBuffer +=
                    BufferedNotification(
                        input = input,
                        eventId = eventIdFactory(),
                        capturedAtEpochMillis = clock(),
                    )
                initializationDiagnostics.onInitializationBuffered(initializationBuffer.size)
                return true
            }
            return dispatchIfSelected(input, allowlistProvider())
        }
    }

    fun onSelectionLoadCompleted() {
        synchronized(initializationLock) {
            if (initializationComplete || selectionLoadStateProvider() is SelectionLoadState.Loading) return
            val allowlist = allowlistProvider()
            while (initializationBuffer.isNotEmpty()) {
                val buffered = initializationBuffer.removeFirst()
                dispatchIfSelected(
                    input = buffered.input,
                    allowlist = allowlist,
                    eventId = buffered.eventId,
                    capturedAtEpochMillis = buffered.capturedAtEpochMillis,
                )
            }
            initializationComplete = true
            initializationDiagnostics.onInitializationBufferDrained()
        }
    }

    private fun dispatchIfSelected(
        input: LightweightPostedNotification,
        allowlist: AllowlistSnapshot,
        eventId: String? = null,
        capturedAtEpochMillis: Long? = null,
    ): Boolean {
        val source = allowlist.sourceFor(input.packageName) ?: return false
        dispatcher.dispatch(
            DispatchedNotification(
                eventId = eventId ?: eventIdFactory(),
                capturedAtEpochMillis = capturedAtEpochMillis ?: clock(),
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
                        groupKey = input.groupKey,
                        overrideGroupKey = input.overrideGroupKey,
                    ),
                source = source,
                notificationHandle = input.notificationHandle,
            ),
        )
        return true
    }

    private data class BufferedNotification(
        val input: LightweightPostedNotification,
        val eventId: String,
        val capturedAtEpochMillis: Long,
    )

    private data object NoOpInitializationCaptureDiagnostics : InitializationCaptureDiagnostics {
        override fun onInitializationBuffered(depth: Int) = Unit

        override fun onInitializationOverflow() = Unit

        override fun onInitializationBufferDrained() = Unit
    }

    private companion object {
        const val INITIALIZATION_BUFFER_CAPACITY = 1_024
    }
}
