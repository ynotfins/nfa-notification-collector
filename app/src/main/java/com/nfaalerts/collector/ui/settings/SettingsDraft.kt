package com.nfaalerts.collector.ui.settings

import com.nfaalerts.collector.config.CollectorConfigCodec
import com.nfaalerts.collector.config.CollectorConfigDocument
import com.nfaalerts.collector.config.ConfigDecodeResult
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

data class EndpointProfileDraft(
    val name: String,
    val baseUrl: String,
    val ingestPath: String,
)

data class SettingsDraft(
    private val preservedRoot: JsonObject,
    val profiles: List<EndpointProfileDraft>,
    val activeProfile: String,
    val deviceId: String,
    val connectTimeoutMs: String,
    val readTimeoutMs: String,
    val initialBackoffMs: String,
    val maxBackoffMs: String,
    val sentDays: String,
    val maxSentRows: String,
    val diagnosticsDays: String,
    val diagnosticsMaxRows: String,
) {
    fun resetToApprovedDefaults(): SettingsDraft = from(CollectorConfigCodec().defaultDocument())

    fun decode(codec: CollectorConfigCodec = CollectorConfigCodec()): ConfigDecodeResult =
        codec.decode(encodedRoot().toString().encodeToByteArray())

    fun encodedPayload(): ByteArray = encodedRoot().toString().encodeToByteArray()

    private fun encodedRoot(): JsonObject {
        val root = preservedRoot.toMutableMap()
        root["deviceId"] = JsonPrimitive(deviceId)
        root["activeEndpointProfile"] = JsonPrimitive(activeProfile)
        val previousProfiles = (preservedRoot["endpointProfiles"] as? JsonObject).orEmpty()
        root["endpointProfiles"] =
            JsonObject(
                profiles.associate { profile ->
                    val previous = previousProfiles[profile.name] as? JsonObject
                    profile.name to
                        previous.withFields(
                            "baseUrl" to JsonPrimitive(profile.baseUrl),
                            "ingestPath" to JsonPrimitive(profile.ingestPath),
                        )
                },
            )
        root["delivery"] =
            (preservedRoot["delivery"] as? JsonObject).withFields(
                "connectTimeoutMs" to numeric(connectTimeoutMs),
                "readTimeoutMs" to numeric(readTimeoutMs),
                "initialBackoffMs" to numeric(initialBackoffMs),
                "maxBackoffMs" to numeric(maxBackoffMs),
            )
        root["retention"] =
            (preservedRoot["retention"] as? JsonObject).withFields(
                "sentDays" to numeric(sentDays),
                "maxSentRows" to numeric(maxSentRows),
            )
        root["diagnostics"] =
            (preservedRoot["diagnostics"] as? JsonObject).withFields(
                "retentionDays" to numeric(diagnosticsDays),
                "maxRows" to numeric(diagnosticsMaxRows),
            )
        return JsonObject(root)
    }

    companion object {
        fun from(document: CollectorConfigDocument): SettingsDraft {
            val config = document.config
            return SettingsDraft(
                preservedRoot = document.root,
                profiles =
                    config.endpointProfiles
                        .map { (name, profile) ->
                            EndpointProfileDraft(name, profile.baseUrl, profile.ingestPath)
                        }.sortedBy(EndpointProfileDraft::name),
                activeProfile = config.activeEndpointProfile,
                deviceId = config.deviceId,
                connectTimeoutMs = config.delivery.connectTimeoutMs.toString(),
                readTimeoutMs = config.delivery.readTimeoutMs.toString(),
                initialBackoffMs = config.delivery.initialBackoffMs.toString(),
                maxBackoffMs = config.delivery.maxBackoffMs.toString(),
                sentDays = config.retention.sentDays.toString(),
                maxSentRows = config.retention.maxSentRows.toString(),
                diagnosticsDays = config.diagnostics.retentionDays.toString(),
                diagnosticsMaxRows = config.diagnostics.maxRows.toString(),
            )
        }

        private fun numeric(value: String): JsonPrimitive =
            value.toLongOrNull()?.let(::JsonPrimitive) ?: JsonPrimitive(value)

        private fun JsonObject?.withFields(vararg values: Pair<String, JsonElement>): JsonObject =
            JsonObject(orEmpty().toMutableMap().apply { putAll(values) })
    }
}
