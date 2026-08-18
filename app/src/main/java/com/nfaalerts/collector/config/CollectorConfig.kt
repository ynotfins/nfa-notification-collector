package com.nfaalerts.collector.config

import com.nfaalerts.collector.capture.RawTextField
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
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
        if (payload.size > MAX_PAYLOAD_BYTES) {
            return ConfigDecodeResult.Invalid(
                errors =
                    listOf(
                        ConfigValidationError(
                            "/",
                            "CONFIG_PAYLOAD_LIMIT",
                            "Configuration exceeds 1 MiB.",
                        ),
                    ),
                originalPayload = ByteArray(0),
            )
        }
        val original = payload.copyOf()
        val root =
            try {
                Json.parseToJsonElement(payload.toString(StandardCharsets.UTF_8)).jsonObject
            } catch (_: Exception) {
                return invalid(original, "/", "INVALID_JSON", "Configuration is not valid JSON.")
            }

        val versionElement = root["configVersion"]
        val legacyVersionElement = root["version"]
        val version = (versionElement as? JsonPrimitive)?.intOrNull
        val legacyVersion = (legacyVersionElement as? JsonPrimitive)?.intOrNull
        if (versionElement != null && version == null) {
            return invalid(original, "/configVersion", "VERSION_TYPE", "Configuration version must be an integer.")
        }
        if (versionElement == null && legacyVersionElement != null && legacyVersion == null) {
            return invalid(original, "/version", "VERSION_TYPE", "Legacy version must be an integer.")
        }
        if (version != null && version > CURRENT_VERSION) {
            return invalid(
                original,
                "/configVersion",
                "FUTURE_VERSION_UNSUPPORTED",
                "Configuration version is newer than this app supports.",
            )
        }
        if (version != null && version != CURRENT_VERSION) {
            return invalid(original, "/configVersion", "VERSION_UNSUPPORTED", "Configuration version is unsupported.")
        }
        if (version == null && legacyVersion != 0) {
            return invalid(original, "/configVersion", "VERSION_REQUIRED", "Configuration version is required.")
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
        document.root.toString().toByteArray(StandardCharsets.UTF_8).also {
            require(it.size <= MAX_PAYLOAD_BYTES) { "CONFIG_PAYLOAD_LIMIT" }
        }

    fun normalizeRoot(root: JsonObject): ConfigDecodeResult = decode(normalize(root).toString().encodeToByteArray())

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
        findProhibitedFields(root, "", errors)
        val deviceElement = root["deviceId"]
        val deviceId = (deviceElement as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull.orEmpty()
        if (!DEVICE_ID.matches(deviceId)) {
            errors += ConfigValidationError("/deviceId", "DEVICE_ID_FORMAT", "Device ID is invalid.")
        }

        val activeElement = root["activeEndpointProfile"]
        val active = (activeElement as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull.orEmpty()
        if (activeElement !is JsonPrimitive || !activeElement.isString) {
            errors +=
                ConfigValidationError(
                    "/activeEndpointProfile",
                    "STRING_REQUIRED",
                    "Active endpoint profile must be a string.",
                )
        }
        val profiles = root["endpointProfiles"] as? JsonObject
        if (profiles == null) {
            errors +=
                ConfigValidationError(
                    "/endpointProfiles",
                    "OBJECT_REQUIRED",
                    "Endpoint profiles must be an object.",
                )
        }
        if (profiles == null || active !in profiles) {
            errors +=
                ConfigValidationError(
                    "/activeEndpointProfile",
                    "ACTIVE_ENDPOINT_MISSING",
                    "Active endpoint profile does not exist.",
                )
        }
        profiles?.forEach { (name, element) ->
            val profilePath = pointer("/endpointProfiles", name)
            val profile = element as? JsonObject
            if (profile == null) {
                errors +=
                    ConfigValidationError(
                        profilePath,
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
                        pointer(profilePath, "baseUrl"),
                        "HTTPS_REQUIRED",
                        "Endpoint must use HTTPS.",
                    )
            }
            val pathElement = profile["ingestPath"]
            val ingestPath = (pathElement as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull.orEmpty()
            if (!ingestPath.startsWith("/") || ingestPath.startsWith("//")) {
                errors +=
                    ConfigValidationError(
                        pointer(profilePath, "ingestPath"),
                        "INGEST_PATH_FORMAT",
                        "Ingest path must start with one slash.",
                    )
            }
        }
        val sources = root["sources"] as? JsonArray
        if (sources == null) {
            errors += ConfigValidationError("/sources", "ARRAY_REQUIRED", "Sources must be an array.")
        } else if (sources.size > MAX_SELECTED_SOURCES) {
            errors += ConfigValidationError("/sources", "TOO_MANY_SOURCES", "At most ten sources are allowed.")
        }
        sources?.let { validateSources(it, errors) }
        listOf("delivery", "retention", "diagnostics").forEach { section ->
            if (root[section] !is JsonObject) {
                errors +=
                    ConfigValidationError("/$section", "OBJECT_REQUIRED", "Configuration section must be an object.")
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
                    "/$section/$key",
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
                    "/$section/$key",
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
                    val childPath = pointer(path, key)
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
                element.forEachIndexed {
                    index,
                    value,
                    ->
                    findProhibitedFields(value, pointer(path, index.toString()), errors)
                }
            }

            is JsonPrimitive -> {
                if (element.isString && element.content.startsWith("Bearer ", ignoreCase = true)) {
                    errors +=
                        ConfigValidationError(
                            path.ifEmpty { "/" },
                            "PROHIBITED_CONFIG_VALUE",
                            "Authentication values are not allowed in configuration.",
                        )
                }
            }
        }
    }

    private fun validateSources(
        sources: JsonArray,
        errors: MutableList<ConfigValidationError>,
    ) {
        val firstPackageIndex = mutableMapOf<String, Int>()
        sources.forEachIndexed { index, element ->
            val path = "/sources/$index"
            val source = element as? JsonObject
            if (source == null) {
                errors += ConfigValidationError(path, "SOURCE_OBJECT_REQUIRED", "Source must be an object.")
                return@forEachIndexed
            }
            val packageName = validateRequiredString(source, "packageName", path, errors)
            validateRequiredString(source, "appLabel", path, errors)
            val sourceId = validateRequiredString(source, "sourceId", path, errors)
            validateRequiredBoolean(source, "enabled", path, errors)
            val confirmed = validateRequiredBoolean(source, "bnnMappingConfirmed", path, errors)
            validateRawTextOrder(source, path, errors)

            if (packageName != null) {
                val first = firstPackageIndex.putIfAbsent(packageName, index)
                if (first != null) {
                    errors +=
                        ConfigValidationError(
                            pointer(path, "packageName"),
                            "DUPLICATE_SOURCE_PACKAGE",
                            "Source package is duplicated.",
                        )
                }
            }
            if (sourceId == "bnn" && confirmed == false) {
                errors +=
                    ConfigValidationError(
                        pointer(path, "bnnMappingConfirmed"),
                        "BNN_CONFIRMATION_REQUIRED",
                        "BNN mapping requires confirmation.",
                    )
            } else if (sourceId != null && sourceId != "bnn" && confirmed == true) {
                errors +=
                    ConfigValidationError(
                        pointer(path, "bnnMappingConfirmed"),
                        "BNN_CONFIRMATION_FORBIDDEN",
                        "Only BNN mappings may be confirmed as BNN.",
                    )
            }
        }
    }

    private fun validateRequiredString(
        source: JsonObject,
        key: String,
        parentPath: String,
        errors: MutableList<ConfigValidationError>,
    ): String? {
        val path = pointer(parentPath, key)
        val primitive = source[key] as? JsonPrimitive
        val value = primitive?.takeIf(JsonPrimitive::isString)?.contentOrNull
        if (value.isNullOrBlank()) {
            errors += ConfigValidationError(path, "NONBLANK_STRING_REQUIRED", "Value must be a nonblank string.")
            return null
        }
        return value
    }

    private fun validateRequiredBoolean(
        source: JsonObject,
        key: String,
        parentPath: String,
        errors: MutableList<ConfigValidationError>,
    ): Boolean? {
        val path = pointer(parentPath, key)
        val primitive = source[key] as? JsonPrimitive
        val value = primitive?.takeUnless(JsonPrimitive::isString)?.booleanOrNull
        if (value == null) {
            errors += ConfigValidationError(path, "BOOLEAN_REQUIRED", "Value must be a boolean.")
        }
        return value
    }

    private fun validateRawTextOrder(
        source: JsonObject,
        parentPath: String,
        errors: MutableList<ConfigValidationError>,
    ) {
        val path = pointer(parentPath, "rawTextOrder")
        val values = source["rawTextOrder"] as? JsonArray
        if (values == null || values.isEmpty()) {
            errors +=
                ConfigValidationError(path, "NONEMPTY_ARRAY_REQUIRED", "Raw-text priority must be a nonempty array.")
            return
        }
        val allowed = RawTextField.entries.mapTo(mutableSetOf(), RawTextField::configValue)
        val seen = mutableSetOf<String>()
        values.forEachIndexed { index, element ->
            val itemPath = pointer(path, index.toString())
            val primitive = element as? JsonPrimitive
            val value = primitive?.takeIf(JsonPrimitive::isString)?.contentOrNull
            when {
                value == null || value !in allowed -> {
                    errors +=
                        ConfigValidationError(
                            itemPath,
                            "RAW_TEXT_FIELD_UNKNOWN",
                            "Raw-text field is not supported.",
                        )
                }

                !seen.add(value) -> {
                    errors +=
                        ConfigValidationError(
                            itemPath,
                            "RAW_TEXT_FIELD_DUPLICATE",
                            "Raw-text field is duplicated.",
                        )
                }
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

    private fun isProhibitedField(key: String): Boolean {
        val normalized = key.lowercase().filter(Char::isLetterOrDigit)
        val segments =
            key
                .replace(Regex("([a-z0-9])([A-Z])"), "$1 $2")
                .split(Regex("[^A-Za-z0-9]+"))
                .filter(String::isNotEmpty)
                .map(String::lowercase)
        val pairs = segments.zipWithNext().map { (first, second) -> first + second }
        return segments.any { it in PROHIBITED_SEGMENTS } ||
            pairs.any { it in PROHIBITED_COMPOUNDS } ||
            normalized in PROHIBITED_FIELDS
    }

    private fun pointer(
        parent: String,
        segment: String,
    ): String = "$parent/${segment.replace("~", "~0").replace("/", "~1")}"

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
        const val MAX_PAYLOAD_BYTES = 1_048_576
        private const val LEGACY_PROFILE = "legacy"
        private val DEVICE_ID = Regex("[A-Za-z0-9._:-]{1,128}")
        private val PROHIBITED_FIELDS =
            setOf(
                "deliveryrows",
                "iv",
                "keyalias",
                "notificationpayload",
            )
        private val PROHIBITED_SEGMENTS =
            setOf(
                "authorization",
                "bearer",
                "ciphertext",
                "credential",
                "password",
                "secret",
                "token",
            )
        private val PROHIBITED_COMPOUNDS = setOf("accesstoken", "apikey", "clientsecret", "privatekey")
    }
}
