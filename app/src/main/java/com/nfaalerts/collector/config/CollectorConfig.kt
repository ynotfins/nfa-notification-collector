package com.nfaalerts.collector.config

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import java.net.URI
import java.nio.charset.StandardCharsets

data class ConfigValidationError(
    val path: String,
    val code: String,
    val safeMessage: String,
)

data class EndpointProfile(
    val baseUrl: String,
    val ingestPath: String,
)

data class DeliveryConfig(
    val connectTimeoutMs: Long,
    val readTimeoutMs: Long,
    val initialBackoffMs: Long,
    val maxBackoffMs: Long,
)

data class RetentionConfig(
    val sentDays: Int,
    val maxSentRows: Int,
)

data class DiagnosticsConfig(
    val retentionDays: Int,
    val maxRows: Int,
)

data class CollectorConfig(
    val configVersion: Int,
    val deviceId: String,
    val activeEndpointProfile: String,
    val endpointProfiles: Map<String, EndpointProfile>,
    val delivery: DeliveryConfig,
    val retention: RetentionConfig,
    val diagnostics: DiagnosticsConfig,
) {
    val activeEndpoint: EndpointProfile
        get() = endpointProfiles.getValue(activeEndpointProfile)
}

data class CollectorConfigDocument(
    val root: JsonObject,
    val config: CollectorConfig,
    val migratedFromVersion: Int? = null,
)

sealed interface ConfigDecodeResult {
    data class Valid(
        val document: CollectorConfigDocument,
    ) : ConfigDecodeResult

    data class Invalid(
        val errors: List<ConfigValidationError>,
        val originalPayload: ByteArray,
    ) : ConfigDecodeResult
}

class CollectorConfigCodec {
    fun defaultDocument(): CollectorConfigDocument =
        requireValid(normalize(JsonObject(emptyMap())), migratedFromVersion = null)

    fun decode(payload: ByteArray): ConfigDecodeResult {
        val original = payload.copyOf()
        val root =
            try {
                Json.parseToJsonElement(payload.toString(StandardCharsets.UTF_8)).jsonObject
            } catch (_: Exception) {
                return invalid(original, "$", "INVALID_JSON", "Configuration is not valid JSON.")
            }

        val versionElement = root["configVersion"]
        val legacyVersionElement = root["version"]
        val version = (versionElement as? JsonPrimitive)?.intOrNull
        val legacyVersion = (legacyVersionElement as? JsonPrimitive)?.intOrNull
        if (versionElement != null && version == null) {
            return invalid(original, "$.configVersion", "VERSION_TYPE", "Configuration version must be an integer.")
        }
        if (versionElement == null && legacyVersionElement != null && legacyVersion == null) {
            return invalid(original, "$.version", "VERSION_TYPE", "Legacy version must be an integer.")
        }
        if (version != null && version > CURRENT_VERSION) {
            return invalid(
                original,
                "$.configVersion",
                "FUTURE_VERSION_UNSUPPORTED",
                "Configuration version is newer than this app supports.",
            )
        }
        if (version != null && version != CURRENT_VERSION) {
            return invalid(original, "$.configVersion", "VERSION_UNSUPPORTED", "Configuration version is unsupported.")
        }
        if (version == null && legacyVersion != 0) {
            return invalid(original, "$.configVersion", "VERSION_REQUIRED", "Configuration version is required.")
        }

        val migrated = if (legacyVersion == 0 && version == null) migrateV0(root) else root
        val normalized = normalize(migrated)
        val errors = validate(normalized)
        return if (errors.isEmpty()) {
            ConfigDecodeResult.Valid(
                requireValid(normalized, migratedFromVersion = if (legacyVersion == 0) 0 else null),
            )
        } else {
            ConfigDecodeResult.Invalid(errors, original)
        }
    }

    fun importPayload(payload: ByteArray): ConfigDecodeResult = decode(payload)

    fun isExportable(payload: ByteArray): Boolean = decode(payload) is ConfigDecodeResult.Valid

    fun exportPayload(document: CollectorConfigDocument): ByteArray =
        canonicalize(document.root).toString().toByteArray(StandardCharsets.UTF_8)

    fun normalizeRoot(root: JsonObject): ConfigDecodeResult =
        decode(canonicalize(normalize(root)).toString().encodeToByteArray())

    private fun requireValid(
        root: JsonObject,
        migratedFromVersion: Int?,
    ): CollectorConfigDocument {
        val endpoints =
            root.getValue("endpointProfiles").jsonObject.mapValues { (_, element) ->
                val value = element.jsonObject
                EndpointProfile(
                    baseUrl = value.getValue("baseUrl").jsonPrimitive.content,
                    ingestPath = value.getValue("ingestPath").jsonPrimitive.content,
                )
            }
        val delivery = root.getValue("delivery").jsonObject
        val retention = root.getValue("retention").jsonObject
        val diagnostics = root.getValue("diagnostics").jsonObject
        return CollectorConfigDocument(
            root = root,
            config =
                CollectorConfig(
                    configVersion = CURRENT_VERSION,
                    deviceId = root.getValue("deviceId").jsonPrimitive.content,
                    activeEndpointProfile = root.getValue("activeEndpointProfile").jsonPrimitive.content,
                    endpointProfiles = endpoints,
                    delivery =
                        DeliveryConfig(
                            connectTimeoutMs = delivery.getValue("connectTimeoutMs").jsonPrimitive.long,
                            readTimeoutMs = delivery.getValue("readTimeoutMs").jsonPrimitive.long,
                            initialBackoffMs = delivery.getValue("initialBackoffMs").jsonPrimitive.long,
                            maxBackoffMs = delivery.getValue("maxBackoffMs").jsonPrimitive.long,
                        ),
                    retention =
                        RetentionConfig(
                            sentDays = retention.getValue("sentDays").jsonPrimitive.int,
                            maxSentRows = retention.getValue("maxSentRows").jsonPrimitive.int,
                        ),
                    diagnostics =
                        DiagnosticsConfig(
                            retentionDays = diagnostics.getValue("retentionDays").jsonPrimitive.int,
                            maxRows = diagnostics.getValue("maxRows").jsonPrimitive.int,
                        ),
                ),
            migratedFromVersion = migratedFromVersion,
        )
    }

    private fun validate(root: JsonObject): List<ConfigValidationError> {
        val errors = mutableListOf<ConfigValidationError>()
        findProhibitedFields(root, "$", errors)
        val deviceElement = root["deviceId"]
        val deviceId = (deviceElement as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull.orEmpty()
        if (!DEVICE_ID.matches(deviceId)) {
            errors += ConfigValidationError("$.deviceId", "DEVICE_ID_FORMAT", "Device ID is invalid.")
        }

        val activeElement = root["activeEndpointProfile"]
        val active = (activeElement as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull.orEmpty()
        if (activeElement !is JsonPrimitive || !activeElement.isString) {
            errors +=
                ConfigValidationError(
                    "$.activeEndpointProfile",
                    "STRING_REQUIRED",
                    "Active endpoint profile must be a string.",
                )
        }
        val profiles = root["endpointProfiles"] as? JsonObject
        if (profiles == null) {
            errors +=
                ConfigValidationError("$.endpointProfiles", "OBJECT_REQUIRED", "Endpoint profiles must be an object.")
        }
        if (profiles == null || active !in profiles) {
            errors +=
                ConfigValidationError(
                    "$.activeEndpointProfile",
                    "ACTIVE_ENDPOINT_MISSING",
                    "Active endpoint profile does not exist.",
                )
        }
        profiles?.forEach { (name, element) ->
            val profile = element as? JsonObject
            if (profile == null) {
                errors +=
                    ConfigValidationError(
                        "$.endpointProfiles.$name",
                        "ENDPOINT_OBJECT_REQUIRED",
                        "Endpoint must be an object.",
                    )
                return@forEach
            }
            val baseElement = profile["baseUrl"]
            val baseUrl = (baseElement as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull.orEmpty()
            val uri = runCatching { URI(baseUrl) }.getOrNull()
            if (uri?.scheme != "https" || uri.host.isNullOrBlank() || uri.userInfo != null) {
                errors +=
                    ConfigValidationError(
                        "$.endpointProfiles.$name.baseUrl",
                        "HTTPS_REQUIRED",
                        "Endpoint must use HTTPS.",
                    )
            }
            val pathElement = profile["ingestPath"]
            val ingestPath = (pathElement as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull.orEmpty()
            if (!ingestPath.startsWith("/") || ingestPath.startsWith("//")) {
                errors +=
                    ConfigValidationError(
                        "$.endpointProfiles.$name.ingestPath",
                        "INGEST_PATH_FORMAT",
                        "Ingest path must start with one slash.",
                    )
            }
        }
        val sources = root["sources"] as? JsonArray
        if (sources == null) {
            errors += ConfigValidationError("$.sources", "ARRAY_REQUIRED", "Sources must be an array.")
        } else if (sources.size > MAX_SELECTED_SOURCES) {
            errors += ConfigValidationError("$.sources", "TOO_MANY_SOURCES", "At most ten sources are allowed.")
        }
        listOf("delivery", "retention", "diagnostics").forEach { section ->
            if (root[section] !is JsonObject) {
                errors +=
                    ConfigValidationError("$.$section", "OBJECT_REQUIRED", "Configuration section must be an object.")
            }
        }
        validatePositiveLong(root, "delivery", "connectTimeoutMs", errors)
        validatePositiveLong(root, "delivery", "readTimeoutMs", errors)
        validatePositiveLong(root, "delivery", "initialBackoffMs", errors)
        validatePositiveLong(root, "delivery", "maxBackoffMs", errors)
        validatePositiveInt(root, "retention", "sentDays", errors)
        validatePositiveInt(root, "retention", "maxSentRows", errors)
        validatePositiveInt(root, "diagnostics", "retentionDays", errors)
        validatePositiveInt(root, "diagnostics", "maxRows", errors)
        return errors.distinct()
    }

    private fun validatePositiveLong(
        root: JsonObject,
        section: String,
        key: String,
        errors: MutableList<ConfigValidationError>,
    ) {
        val value = ((root[section] as? JsonObject)?.get(key) as? JsonPrimitive)?.longOrNull
        if (value == null || value <= 0L) {
            errors +=
                ConfigValidationError(
                    "$.$section.$key",
                    "POSITIVE_INTEGER_REQUIRED",
                    "Value must be a positive integer.",
                )
        }
    }

    private fun validatePositiveInt(
        root: JsonObject,
        section: String,
        key: String,
        errors: MutableList<ConfigValidationError>,
    ) {
        val value = ((root[section] as? JsonObject)?.get(key) as? JsonPrimitive)?.intOrNull
        if (value == null || value <= 0) {
            errors +=
                ConfigValidationError(
                    "$.$section.$key",
                    "POSITIVE_INTEGER_REQUIRED",
                    "Value must be a positive integer.",
                )
        }
    }

    private fun findProhibitedFields(
        element: JsonElement,
        path: String,
        errors: MutableList<ConfigValidationError>,
    ) {
        when (element) {
            is JsonObject -> {
                element.forEach { (key, value) ->
                    val childPath = "$path.$key"
                    if (isProhibitedField(key)) {
                        errors +=
                            ConfigValidationError(
                                childPath,
                                "PROHIBITED_CONFIG_FIELD",
                                "Secrets and private payloads are not allowed in configuration.",
                            )
                    }
                    findProhibitedFields(value, childPath, errors)
                }
            }

            is JsonArray -> {
                element.forEachIndexed { index, value -> findProhibitedFields(value, "$path[$index]", errors) }
            }

            else -> {
                Unit
            }
        }
    }

    private fun normalize(root: JsonObject): JsonObject {
        val result = root.toMutableMap()
        result["configVersion"] = JsonPrimitive(CURRENT_VERSION)
        result["deviceId"] = result["deviceId"] ?: JsonPrimitive(DEFAULT_DEVICE_ID)
        result["activeEndpointProfile"] = result["activeEndpointProfile"] ?: JsonPrimitive(DEFAULT_PROFILE)
        val defaultEndpoints =
            JsonObject(
                mapOf(
                    DEFAULT_PROFILE to
                        JsonObject(
                            mapOf(
                                "baseUrl" to JsonPrimitive(DEFAULT_BASE_URL),
                                "ingestPath" to JsonPrimitive(DEFAULT_INGEST_PATH),
                            ),
                        ),
                ),
            )
        result["endpointProfiles"] = mergeKnownObject(defaultEndpoints, result["endpointProfiles"])
        result["sources"] = result["sources"] ?: JsonArray(emptyList())
        val defaultDelivery =
            JsonObject(
                mapOf(
                    "connectTimeoutMs" to JsonPrimitive(15_000L),
                    "initialBackoffMs" to JsonPrimitive(30_000L),
                    "maxBackoffMs" to JsonPrimitive(21_600_000L),
                    "readTimeoutMs" to JsonPrimitive(30_000L),
                ),
            )
        result["delivery"] = mergeKnownObject(defaultDelivery, result["delivery"])
        result["retention"] =
            mergeKnownObject(
                JsonObject(mapOf("maxSentRows" to JsonPrimitive(10_000), "sentDays" to JsonPrimitive(90))),
                result["retention"],
            )
        result["diagnostics"] =
            mergeKnownObject(
                JsonObject(mapOf("maxRows" to JsonPrimitive(2_000), "retentionDays" to JsonPrimitive(14))),
                result["diagnostics"],
            )
        return JsonObject(result)
    }

    private fun mergeKnownObject(
        defaults: JsonObject,
        existing: JsonElement?,
    ): JsonElement =
        when (existing) {
            null -> defaults
            is JsonObject -> JsonObject(defaults.toMutableMap().apply { putAll(existing) })
            else -> existing
        }

    private fun migrateV0(root: JsonObject): JsonObject {
        val result = root.toMutableMap()
        val baseUrl =
            (result.remove("serverUrl") as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull
                ?: DEFAULT_BASE_URL
        val ingestPath =
            (result.remove("ingestPath") as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull
                ?: DEFAULT_INGEST_PATH
        result.remove("version")
        result["configVersion"] = JsonPrimitive(CURRENT_VERSION)
        result["activeEndpointProfile"] = JsonPrimitive(LEGACY_PROFILE)
        result["endpointProfiles"] =
            JsonObject(
                mapOf(
                    LEGACY_PROFILE to
                        JsonObject(
                            mapOf(
                                "baseUrl" to JsonPrimitive(baseUrl),
                                "ingestPath" to JsonPrimitive(ingestPath),
                            ),
                        ),
                ),
            )
        return JsonObject(result)
    }

    private fun canonicalize(element: JsonElement): JsonElement =
        when (element) {
            is JsonObject -> JsonObject(element.toSortedMap().mapValues { canonicalize(it.value) })
            is JsonArray -> JsonArray(element.map(::canonicalize))
            is JsonPrimitive -> element
            JsonNull -> JsonNull
        }

    private fun isProhibitedField(key: String): Boolean {
        val normalized = key.lowercase().filter(Char::isLetterOrDigit)
        return normalized.contains("bearer") ||
            normalized.contains("authorization") ||
            normalized.endsWith("token") ||
            normalized.contains("ciphertext") ||
            normalized in PROHIBITED_FIELDS
    }

    private fun invalid(
        original: ByteArray,
        path: String,
        code: String,
        message: String,
    ) = ConfigDecodeResult.Invalid(listOf(ConfigValidationError(path, code, message)), original)

    companion object {
        const val CURRENT_VERSION = 1
        const val DEFAULT_DEVICE_ID = "nfa-primary-phone"
        const val DEFAULT_PROFILE = "tailscale"
        const val DEFAULT_BASE_URL = "https://chaoscentral.tailb71e7e.ts.net"
        const val DEFAULT_INGEST_PATH = "/v1/ingest/alerts"
        private const val LEGACY_PROFILE = "legacy"
        private val DEVICE_ID = Regex("[A-Za-z0-9._:-]{1,128}")
        private val PROHIBITED_FIELDS =
            setOf(
                "deliveryrows",
                "iv",
                "keyalias",
                "notificationpayload",
                "token",
            )
    }
}
