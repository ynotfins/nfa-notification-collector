package com.nfaalerts.collector.config

import android.content.Context
import android.util.AtomicFile
import com.nfaalerts.collector.capture.RawTextField
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.charset.StandardCharsets

class JsonSourceSelectionStore(
    context: Context,
    fileName: String = FILE_NAME,
    legacyFileName: String = LEGACY_FILE_NAME,
) : SourceSelectionStore {
    private val file = AtomicFile(File(context.filesDir, fileName))
    private val legacyFile = AtomicFile(File(context.filesDir, legacyFileName))
    private val codec = CollectorConfigCodec()

    override suspend fun load(): List<SourceSelection> =
        withContext(Dispatchers.IO) {
            if (file.baseFile.exists()) {
                return@withContext parseCanonical(read(file)).sources()
            }
            migrateLegacyIfPresent()
            if (!file.baseFile.exists()) emptyList() else parseCanonical(read(file)).sources()
        }

    override suspend fun save(selections: List<SourceSelection>) {
        withContext(Dispatchers.IO) {
            validateSelections(selections)
            val existing = if (file.baseFile.exists()) parseCanonical(read(file)) else emptyCanonicalRoot()
            writeAndValidate(existing.withSources(selections), selections)
            if (legacyFile.baseFile.exists()) {
                legacyFile.delete()
            }
        }
    }

    private fun migrateLegacyIfPresent() {
        if (!legacyFile.baseFile.exists()) return
        val legacyRoot = parseRoot(read(legacyFile))
        val version =
            legacyRoot["version"]?.jsonPrimitive?.content?.toIntOrNull()
                ?: throw InvalidSelectionConfigException("INVALID_LEGACY_CONFIG")
        if (version != FORMAT_VERSION) {
            throw InvalidSelectionConfigException("UNKNOWN_LEGACY_CONFIG_VERSION")
        }
        val selections = legacyRoot.sources()
        validateSelections(selections)
        val existing = if (file.baseFile.exists()) parseCanonical(read(file)) else emptyCanonicalRoot()
        writeAndValidate(existing.withSources(selections), selections)
        legacyFile.delete()
    }

    private fun writeAndValidate(
        root: JsonObject,
        expectedSelections: List<SourceSelection>,
    ) {
        write(file, root.toString())
        val reopened = parseCanonical(read(file)).sources()
        if (reopened != expectedSelections.sortedBy(SourceSelection::packageName)) {
            throw InvalidSelectionConfigException("CONFIG_WRITE_VALIDATION_FAILED")
        }
    }

    private fun parseCanonical(content: String): JsonObject =
        when (val decoded = codec.decode(content.encodeToByteArray())) {
            is ConfigDecodeResult.Valid -> {
                decoded.document.root
            }

            is ConfigDecodeResult.Invalid -> {
                val first = decoded.errors.first()
                val code =
                    when (first.code) {
                        "FUTURE_VERSION_UNSUPPORTED" -> "UNKNOWN_CONFIG_VERSION"
                        "INVALID_JSON" -> "INVALID_CONFIG"
                        else -> first.code
                    }
                throw InvalidSelectionConfigException(code)
            }
        }

    private fun parseRoot(content: String): JsonObject =
        try {
            Json.parseToJsonElement(content).jsonObject
        } catch (_: Exception) {
            throw InvalidSelectionConfigException("INVALID_CONFIG")
        }

    private fun JsonObject.sources(): List<SourceSelection> =
        (this["sources"]?.jsonArray ?: throw InvalidSelectionConfigException("INVALID_CONFIG"))
            .map { element ->
                val value = element.jsonObject
                SourceSelection(
                    packageName = value.getValue("packageName").jsonPrimitive.content,
                    appLabel = value.getValue("appLabel").jsonPrimitive.content,
                    sourceId = value.getValue("sourceId").jsonPrimitive.content,
                    enabled = value.getValue("enabled").jsonPrimitive.boolean,
                    bnnMappingConfirmed = value.getValue("bnnMappingConfirmed").jsonPrimitive.boolean,
                    rawTextOrder =
                        value
                            .getValue("rawTextOrder")
                            .jsonArray
                            .mapNotNull { field ->
                                RawTextField.entries.firstOrNull { it.configValue == field.jsonPrimitive.content }
                            }.ifEmpty { RawTextField.DEFAULT_ORDER },
                )
            }.sortedBy(SourceSelection::packageName)

    private fun JsonObject.withSources(selections: List<SourceSelection>): JsonObject =
        JsonObject(
            toMutableMap().apply {
                put("configVersion", JsonPrimitive(FORMAT_VERSION))
                put("sources", selectionsJson(selections))
            },
        )

    private fun selectionsJson(selections: List<SourceSelection>) =
        JsonArray(
            selections.sortedBy(SourceSelection::packageName).map { source ->
                JsonObject(
                    sortedMapOf(
                        "appLabel" to JsonPrimitive(source.appLabel),
                        "bnnMappingConfirmed" to JsonPrimitive(source.bnnMappingConfirmed),
                        "enabled" to JsonPrimitive(source.enabled),
                        "packageName" to JsonPrimitive(source.packageName),
                        "rawTextOrder" to JsonArray(source.rawTextOrder.map { JsonPrimitive(it.configValue) }),
                        "sourceId" to JsonPrimitive(source.sourceId),
                    ),
                )
            },
        )

    private fun validateSelections(selections: List<SourceSelection>) {
        if (selections.size > MAX_SELECTED_SOURCES) throw InvalidSelectionConfigException("TOO_MANY_SOURCES")
        if (selections.map(SourceSelection::packageName).toSet().size != selections.size) {
            throw InvalidSelectionConfigException("DUPLICATE_SOURCE_PACKAGE")
        }
        if (selections.any { it.packageName.isBlank() || it.sourceId.isBlank() || it.rawTextOrder.isEmpty() }) {
            throw InvalidSelectionConfigException("INVALID_SOURCE")
        }
        if (selections.any { it.sourceId == "bnn" && !it.bnnMappingConfirmed }) {
            throw InvalidSelectionConfigException("BNN_CONFIRMATION_REQUIRED")
        }
    }

    private fun emptyCanonicalRoot() = codec.defaultDocument().root

    private fun read(atomicFile: AtomicFile): String = atomicFile.openRead().bufferedReader().use { it.readText() }

    private fun write(
        atomicFile: AtomicFile,
        payload: String,
    ) {
        val stream = atomicFile.startWrite()
        try {
            stream.write(payload.toByteArray(StandardCharsets.UTF_8))
            stream.fd.sync()
            atomicFile.finishWrite(stream)
        } catch (failure: Throwable) {
            atomicFile.failWrite(stream)
            throw failure
        }
    }

    private companion object {
        const val FORMAT_VERSION = 1
        const val FILE_NAME = "collector-config.json"
        const val LEGACY_FILE_NAME = "collector-source-selection-v1.json"
    }
}
