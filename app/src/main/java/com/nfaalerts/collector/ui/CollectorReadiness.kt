package com.nfaalerts.collector.ui

import com.nfaalerts.collector.data.DeliveryState

enum class CollectorReadinessState {
    Ready,
    SetupRequired,
}

data class CollectorReadiness(
    val notificationAccessGranted: Boolean,
    val endpointIsValid: Boolean,
    val bearerSaved: Boolean,
    val deviceIdIsValid: Boolean,
    val enabledSourceCount: Int,
) {
    val state: CollectorReadinessState
        get() =
            if (
                notificationAccessGranted &&
                endpointIsValid &&
                bearerSaved &&
                deviceIdIsValid &&
                enabledSourceCount > 0
            ) {
                CollectorReadinessState.Ready
            } else {
                CollectorReadinessState.SetupRequired
            }
}

enum class CollectorDestination {
    Status,
    Sources,
    Delivery,
    Settings,
}

data class DeliveryUiRow(
    val eventId: String,
    val sourceId: String,
    val state: String,
    val attempts: Int,
    val httpStatus: Int?,
    val safeFailure: String?,
    val serverId: String?,
    val occurredAt: Long,
    val redactedPreview: String,
)

data class CollectorUiSnapshot(
    val readiness: CollectorReadiness,
    val endpoint: String,
    val deviceId: String,
    val selectedCount: Int,
    val queueCount: Int,
    val listenerState: String,
    val lastCapture: String = "Unknown",
    val lastSend: String = "Unknown",
    val lastError: String = "Unknown",
    val networkState: String = "Unknown",
    val batteryState: String = "Unknown",
)

object DeliveryRetryEligibility {
    fun allows(state: DeliveryState): Boolean = state == DeliveryState.RETRY_WAIT
}
