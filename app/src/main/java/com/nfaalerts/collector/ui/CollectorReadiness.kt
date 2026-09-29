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

enum class CollectorDestination(
    val label: String,
) {
    Status("Home"),
    Sources("Sources"),
    Delivery("Queue"),
    Settings("Settings"),
}

enum class GuidedSetupStep {
    Access,
    Endpoint,
    Token,
    Sources,
    Verify,
    Ready,
}

object GuidedSetup {
    fun next(readiness: CollectorReadiness): GuidedSetupStep =
        when {
            !readiness.notificationAccessGranted -> GuidedSetupStep.Access
            !readiness.endpointIsValid -> GuidedSetupStep.Endpoint
            !readiness.bearerSaved -> GuidedSetupStep.Token
            !readiness.deviceIdIsValid -> GuidedSetupStep.Endpoint
            readiness.enabledSourceCount == 0 -> GuidedSetupStep.Sources
            else -> GuidedSetupStep.Ready
        }
}

data class DeliveryUiRow(
    val eventId: String,
    val packageName: String,
    val sourceId: String,
    val state: String,
    val attempts: Int,
    val httpStatus: Int?,
    val safeFailure: String?,
    val serverId: String?,
    val occurredAt: Long,
    val redactedPreview: String,
)

data class DiagnosticUiRow(
    val diagnosticId: String,
    val createdAt: Long,
    val eventCode: String,
    val safeDetails: String,
)

data class CollectorUiSnapshot(
    val readiness: CollectorReadiness,
    val endpoint: String,
    val deviceId: String,
    val selectedCount: Int,
    val queueCount: Long,
    val listenerState: String,
    val listenerConnected: Boolean = false,
    val theme: String = "primary-light",
    val lastCapture: String = "Unknown",
    val lastSend: String = "Unknown",
    val lastDrain: String = "Unknown",
    val lastError: String = "Unknown",
    val networkState: String = "Unknown",
    val batteryState: String = "Unknown",
    val batteryOptimizationState: BatteryOptimizationState = BatteryOptimizationState.Unknown,
    val foregroundNotificationState: ForegroundNotificationState = ForegroundNotificationState.Unknown,
    val backgroundActivityState: BackgroundActivityState = BackgroundActivityState.Unknown,
    val recentsLocked: Boolean = false,
    val totalCount: Long = 0,
    val queueCountsByState: Map<DeliveryState, Long> = emptyMap(),
    val serverReceivedAt: String = "Unknown",
    val verificationComplete: Boolean = false,
    val verificationMessage: String = "Run the safe local verification check.",
    val loading: Boolean = false,
    val notificationAccessState: NotificationAccessState = NotificationAccessState.Unknown,
    val connectivityState: ConnectivityState = ConnectivityState.Unknown,
    val canonicalConfigRevision: String = "",
    val bearerSavedAtEpochMillis: Long? = null,
    val bearerRevisionOk: Boolean = false,
    internal val liveVerificationFingerprint: LiveVerificationFingerprint? = null,
) {
    val operationallyHealthy: Boolean
        get() =
            readiness.state == CollectorReadinessState.Ready &&
                notificationAccessState == NotificationAccessState.Granted &&
                batteryOptimizationState == BatteryOptimizationState.Exempt &&
                backgroundActivityState == BackgroundActivityState.Allowed &&
                foregroundNotificationState in
                setOf(ForegroundNotificationState.Granted, ForegroundNotificationState.NotRequired) &&
                listenerConnected

    val guidedStep: GuidedSetupStep
        get() = GuidedSetup.next(readiness)
}

object DeliveryRetryEligibility {
    fun allows(state: DeliveryState): Boolean = state == DeliveryState.RETRY_WAIT
}
