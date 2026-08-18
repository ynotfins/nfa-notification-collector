package com.nfaalerts.collector.capture

data class SafeOpaqueValue(
    val safeType: String,
    val marker: String,
    val metadata: Map<String, Any?>,
) {
    companion object {
        fun binary(length: Int) =
            SafeOpaqueValue(
                safeType = "binary",
                marker = "binary_omitted",
                metadata = mapOf("length" to length),
            )

        fun executable(runtimeType: String) =
            SafeOpaqueValue(
                safeType = "executable",
                marker = "executable_omitted",
                metadata = mapOf("runtimeType" to runtimeType),
            )
    }
}

data class SafeRemoteInputValue(
    val resultKey: String,
    val label: String?,
    val allowFreeFormInput: Boolean,
    val allowedDataTypes: Set<String>,
)

data class SafeActionValue(
    val title: String?,
    val semanticAction: Int,
    val showsUserInterface: Boolean?,
    val authenticationRequired: Boolean,
    val remoteInputs: List<SafeRemoteInputValue>,
    val hasPendingIntent: Boolean,
)

data class SafeMessageValue(
    val text: String?,
    val timestampEpochMillis: Long,
    val sender: String?,
)
