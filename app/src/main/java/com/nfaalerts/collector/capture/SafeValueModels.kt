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
    val choices: List<String> = emptyList(),
    val editChoicesBeforeSending: Int? = null,
    val extras: Any? = null,
)

data class SafePersonValue(
    val name: String?,
    val uri: String?,
    val key: String?,
    val isBot: Boolean,
    val isImportant: Boolean,
    val icon: Any?,
)

data class SafeActionValue(
    val title: String?,
    val semanticAction: Int,
    val showsUserInterface: Boolean?,
    val authenticationRequired: Boolean,
    val remoteInputs: List<SafeRemoteInputValue>,
    val hasPendingIntent: Boolean,
    val icon: Any? = null,
    val extras: Any? = null,
    val contextual: Boolean? = null,
)

data class SafeMessageValue(
    val text: String?,
    val timestampEpochMillis: Long,
    val sender: SafePersonValue?,
    val dataMimeType: String? = null,
    val dataUri: String? = null,
    val extras: Any? = null,
)
