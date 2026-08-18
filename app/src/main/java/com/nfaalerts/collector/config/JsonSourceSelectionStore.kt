package com.nfaalerts.collector.config

import android.content.Context
import com.nfaalerts.collector.capture.RawTextField
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

class JsonSourceSelectionStore private constructor(
    private val repository: CollectorConfigRepository,
    private val legacyFile: File,
) : SourceSelectionStore {
    constructor(
        context: Context,
        fileName: String = CollectorConfigRepository.FILE_NAME,
        legacyFileName: String = LEGACY_FILE_NAME,
    ) : this(
        CollectorConfigRepository(context, fileName),
        File(context.filesDir, legacyFileName),
    )

    constructor(
        repository: CollectorConfigRepository,
        context: Context,
        legacyFileName: String = LEGACY_FILE_NAME,
    ) : this(repository, File(context.filesDir, legacyFileName))

    override suspend fun load(): List<SourceSelection> =
        when (val loaded = repository.load()) {
            is ConfigLoadResult.Loaded -> {
                loaded.document.root.sources()
            }

            ConfigLoadResult.Missing -> {
                migrateLegacyIfPresent()
                when (val reopened = repository.load()) {
                    is ConfigLoadResult.Loaded -> reopened.document.root.sources()
                    ConfigLoadResult.Missing -> emptyList()
                    is ConfigLoadResult.Invalid -> throw invalid(reopened.errors)
                    ConfigLoadResult.IoFailure -> throw InvalidSelectionConfigException("CONFIG_IO_FAILURE")
                }
            }

            is ConfigLoadResult.Invalid -> {
                throw invalid(loaded.errors)
            }

            ConfigLoadResult.IoFailure -> {
                throw InvalidSelectionConfigException("CONFIG_IO_FAILURE")
            }
        }

    override suspend fun save(selections: List<SourceSelection>) {
        validateSelections(selections)
        when (val saved = repository.updateRoot { it.withSources(selections) }) {
            is ConfigSaveResult.Saved -> {
                val reopened = saved.document.root.sources()
                if (reopened != selections.sortedBy(SourceSelection::packageName)) {
                    throw InvalidSelectionConfigException("CONFIG_WRITE_VALIDATION_FAILED")
                }
                if (legacyFile.exists()) legacyFile.delete()
            }

            is ConfigSaveResult.Rejected -> {
                throw invalid(saved.errors)
            }

            ConfigSaveResult.IoFailure -> {
                throw InvalidSelectionConfigException("CONFIG_IO_FAILURE")
            }
        }
    }

    private suspend fun migrateLegacyIfPresent() {
        if (!legacyFile.exists()) return
        val legacyRoot = parseLegacyRoot(legacyFile.readText())
        val version =
            legacyRoot["version"]?.jsonPrimitive?.content?.toIntOrNull()
                ?: throw InvalidSelectionConfigException("INVALID_LEGACY_CONFIG")
        if (version != FORMAT_VERSION) {
            throw InvalidSelectionConfigException("UNKNOWN_LEGACY_CONFIG_VERSION")
        }
        val selections = legacyRoot.sources()
        validateSelections(selections)
        when (val saved = repository.updateRoot { it.withSources(selections) }) {
            is ConfigSaveResult.Saved -> legacyFile.delete()
            is ConfigSaveResult.Rejected -> throw invalid(saved.errors)
            ConfigSaveResult.IoFailure -> throw InvalidSelectionConfigException("CONFIG_IO_FAILURE")
        }
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
                            .map { field ->
                                RawTextField.entries.single { it.configValue == field.jsonPrimitive.content }
                            },
                )
            }.sortedBy(SourceSelection::packageName)

    private fun JsonObject.withSources(selections: List<SourceSelection>): JsonObject {
        val existing =
            (this["sources"] as? JsonArray)
                ?.mapNotNull { it as? JsonObject }
                ?.associateBy { it["packageName"]?.jsonPrimitive?.content.orEmpty() }
                .orEmpty()
        val updated =
            JsonArray(
                selections.sortedBy(SourceSelection::packageName).map { source ->
                    JsonObject(
                        existing[source.packageName].orEmpty().toMutableMap().apply {
                            put("appLabel", JsonPrimitive(source.appLabel))
                            put("bnnMappingConfirmed", JsonPrimitive(source.bnnMappingConfirmed))
                            put("enabled", JsonPrimitive(source.enabled))
                            put("packageName", JsonPrimitive(source.packageName))
                            put("rawTextOrder", JsonArray(source.rawTextOrder.map { JsonPrimitive(it.configValue) }))
                            put("sourceId", JsonPrimitive(source.sourceId))
                        },
                    )
                },
            )
        return JsonObject(toMutableMap().apply { put("sources", updated) })
    }

    private fun validateSelections(selections: List<SourceSelection>) {
        if (selections.size > MAX_SELECTED_SOURCES) throw InvalidSelectionConfigException("TOO_MANY_SOURCES")
        if (selections.map(SourceSelection::packageName).toSet().size != selections.size) {
            throw InvalidSelectionConfigException("DUPLICATE_SOURCE_PACKAGE")
        }
        if (
            selections.any {
                it.packageName.isBlank() ||
                    it.appLabel.isBlank() ||
                    it.sourceId.isBlank() ||
                    it.rawTextOrder.isEmpty() ||
                    it.rawTextOrder.distinct().size != it.rawTextOrder.size
            }
        ) {
            throw InvalidSelectionConfigException("INVALID_SOURCE")
        }
        if (selections.any { it.sourceId == BNN_SOURCE && !it.bnnMappingConfirmed }) {
            throw InvalidSelectionConfigException("BNN_CONFIRMATION_REQUIRED")
        }
        if (selections.any { it.sourceId != BNN_SOURCE && it.bnnMappingConfirmed }) {
            throw InvalidSelectionConfigException("BNN_CONFIRMATION_FORBIDDEN")
        }
    }

    private fun invalid(errors: List<ConfigValidationError>): InvalidSelectionConfigException {
        val code =
            when (errors.first().code) {
                "FUTURE_VERSION_UNSUPPORTED" -> "UNKNOWN_CONFIG_VERSION"
                "INVALID_JSON" -> "INVALID_CONFIG"
                else -> errors.first().code
            }
        return InvalidSelectionConfigException(code)
    }

    private fun parseLegacyRoot(content: String): JsonObject =
        try {
            Json.parseToJsonElement(content).jsonObject
        } catch (_: Exception) {
            throw InvalidSelectionConfigException("INVALID_CONFIG")
        }

    private companion object {
        const val FORMAT_VERSION = 1
        const val BNN_SOURCE = "bnn"
        const val LEGACY_FILE_NAME = "collector-source-selection-v1.json"
    }
}
