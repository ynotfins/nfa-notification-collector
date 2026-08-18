package com.nfaalerts.collector.data

enum class DeliveryState {
    PENDING,
    SENDING,
    RETRY_WAIT,
    PAUSED_AUTH,
    BLOCKED_CONTRACT,
    QUARANTINED,
    SENT,
}

object InitialDeliveryState {
    fun forSource(sourceId: String): DeliveryState =
        if (sourceId == BNN_SOURCE_ID) DeliveryState.PENDING else DeliveryState.BLOCKED_CONTRACT

    private const val BNN_SOURCE_ID = "bnn"
}
