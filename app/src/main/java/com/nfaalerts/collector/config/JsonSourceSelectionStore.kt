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
) : SourceSelectionStore {
    private val file = AtomicFile(File(context.filesDir, fileName))

    override suspend fun load(): List<SourceSelection> =
        withContext(Dispatchers.IO) {
            if (!file.baseFile.exists()) {
                return@withContext emptyList()
            }
            val root = Json.parseToJsonElement(file.openRead().bufferedReader().use { it.readText() }).jsonObject
            val version =
                root["configVersion"]?.jsonPrimitive?.content?.toIntOrNull()
                    ?: throw InvalidSelectionConfigException("INVALID_CONFIG")
            if (version != FORMAT_VERSION) {
                throw InvalidSelectionConfigException("UNKNOWN_CONFIG_VERSION")
            }
            root.getValue("sources").jsonArray.map { element ->
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
                                RawTextField.entries.firstOrNull {
                                    it.configValue == field.jsonPrimitive.content
                                }
                            }.ifEmpty { RawTextField.DEFAULT_ORDER },
                )
            }
        }

    override suspend fun save(selections: List<SourceSelection>) {
        withContext(Dispatchers.IO) {
            val payload =
                JsonObject(
                    sortedMapOf(
                        "sources" to
                            JsonArray(
                                selections.sortedBy(SourceSelection::packageName).map { source ->
                                    JsonObject(
                                        sortedMapOf(
                                            "appLabel" to JsonPrimitive(source.appLabel),
                                            "bnnMappingConfirmed" to JsonPrimitive(source.bnnMappingConfirmed),
                                            "enabled" to JsonPrimitive(source.enabled),
                                            "packageName" to JsonPrimitive(source.packageName),
                                            "rawTextOrder" to
                                                JsonArray(source.rawTextOrder.map { JsonPrimitive(it.configValue) }),
                                            "sourceId" to JsonPrimitive(source.sourceId),
                                        ),
                                    )
                                },
                            ),
                        "configVersion" to JsonPrimitive(FORMAT_VERSION),
                    ),
                ).toString()
            val stream = file.startWrite()
            try {
                stream.write(payload.toByteArray(StandardCharsets.UTF_8))
                stream.fd.sync()
                file.finishWrite(stream)
            } catch (failure: Throwable) {
                file.failWrite(stream)
                throw failure
            }
        }
    }

    private companion object {
        const val FORMAT_VERSION = 1
        const val FILE_NAME = "collector-config.json"
    }
}
