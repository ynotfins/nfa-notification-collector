@file:Suppress("DEPRECATION")

package com.nfaalerts.collector.capture

import android.annotation.TargetApi
import android.app.PendingIntent
import android.app.Person
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.charset.StandardCharsets
import java.util.IdentityHashMap
import java.lang.reflect.Array as ReflectArray

data class SafeSerializerLimits(
    val maxEnvelopeUtf8Bytes: Int = 2 * 1024 * 1024,
    val maxDepth: Int = 16,
    val maxNodes: Int = 4_096,
    val maxArrayEntries: Int = 1_024,
    val maxStringUtf8Bytes: Int = 1024 * 1024,
)

data class EnvelopeIdentity(
    val eventId: String,
    val packageName: String,
    val notificationKey: String,
    val notificationId: Int,
    val postTimeEpochMillis: Long,
)

sealed interface CanonicalSerialization {
    data class Success(
        val json: String,
        val utf8Bytes: Int,
    ) : CanonicalSerialization

    data class LimitExceeded(
        val limitName: String,
        val path: String,
        val measuredValue: Int,
        val originalSafeType: String,
        val minimalEnvelopeJson: String,
    ) : CanonicalSerialization

    data class Failure(
        val failureType: String,
        val minimalEnvelopeJson: String,
    ) : CanonicalSerialization
}

class SafeCanonicalSerializer(
    private val limits: SafeSerializerLimits = SafeSerializerLimits(),
) {
    fun serialize(
        value: Any?,
        identity: EnvelopeIdentity,
    ): CanonicalSerialization {
        val state = SerializationState()
        return try {
            val json = encode(value, "$", 0, state).toString()
            val utf8Bytes = json.utf8Bytes()
            if (utf8Bytes > limits.maxEnvelopeUtf8Bytes) {
                throw LimitBreach(
                    limitName = "maxEnvelopeUtf8Bytes",
                    path = "$",
                    measuredValue = utf8Bytes,
                    originalSafeType = safeType(value),
                )
            }
            CanonicalSerialization.Success(json, utf8Bytes)
        } catch (breach: LimitBreach) {
            CanonicalSerialization.LimitExceeded(
                limitName = breach.limitName,
                path = breach.path,
                measuredValue = breach.measuredValue,
                originalSafeType = breach.originalSafeType,
                minimalEnvelopeJson = minimalLimitEnvelope(identity, breach).toString(),
            )
        } catch (failure: Exception) {
            CanonicalSerialization.Failure(
                failureType = failure.javaClass.name,
                minimalEnvelopeJson = minimalFailureEnvelope(identity, failure.javaClass.name).toString(),
            )
        }
    }

    private fun encode(
        value: Any?,
        path: String,
        depth: Int,
        state: SerializationState,
    ): JsonElement {
        if (depth > limits.maxDepth) {
            throw LimitBreach("maxDepth", path, depth, safeType(value))
        }
        state.nodes += 1
        if (state.nodes > limits.maxNodes) {
            throw LimitBreach("maxNodes", path, state.nodes, safeType(value))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && value is Person) {
            return encodePerson(value.toSafePerson(), path, depth, state)
        }

        return when (value) {
            null -> {
                typed("null", JsonNull)
            }

            is String -> {
                encodeString(value, path)
            }

            is CharSequence -> {
                encodeString(value.toString(), path)
            }

            is Boolean -> {
                typed("boolean", JsonPrimitive(value))
            }

            is Byte, is Short, is Int, is Long -> {
                typed("integer", JsonPrimitive((value as Number).toLong()))
            }

            is Float, is Double -> {
                typed("number", JsonPrimitive((value as Number).toDouble()))
            }

            is Char -> {
                encodeString(value.toString(), path)
            }

            is Enum<*> -> {
                typed("enum", JsonPrimitive(value.name), "runtimeType" to JsonPrimitive(value.javaClass.name))
            }

            is Bundle -> {
                encodeBundle(value, path, depth, state)
            }

            is Map<*, *> -> {
                encodeMap(value, path, depth, state)
            }

            is List<*> -> {
                encodeIterable(value, path, depth, state, ordered = true)
            }

            is Set<*> -> {
                encodeIterable(value, path, depth, state, ordered = false)
            }

            is Iterable<*> -> {
                encodeIterable(value, path, depth, state, ordered = false)
            }

            is SafeOpaqueValue -> {
                encodeOpaque(value, path, depth, state)
            }

            is SafeActionValue -> {
                encodeAction(value, path, depth, state)
            }

            is SafeMessageValue -> {
                encodeMessage(value, path, depth, state)
            }

            is SafeRemoteInputValue -> {
                encodeRemoteInput(value, path, depth, state)
            }

            is SafePersonValue -> {
                encodePerson(value, path, depth, state)
            }

            is ByteArray -> {
                encodeOpaque(SafeOpaqueValue.binary(value.size), path, depth, state)
            }

            is PendingIntent -> {
                encodeOpaque(SafeOpaqueValue.executable(PendingIntent::class.java.name), path, depth, state)
            }

            is Bitmap -> {
                encodeOpaque(
                    SafeOpaqueValue(
                        safeType = "bitmap",
                        marker = "bitmap_metadata_only",
                        metadata =
                            mapOf(
                                "width" to value.width,
                                "height" to value.height,
                                "config" to value.config?.name,
                            ),
                    ),
                    path,
                    depth,
                    state,
                )
            }

            is Icon -> {
                encodeOpaque(
                    SafeOpaqueValue(
                        safeType = "icon",
                        marker = "icon_metadata_only",
                        metadata =
                            mapOf(
                                "iconType" to
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                                        value.type
                                    } else {
                                        null
                                    },
                            ),
                    ),
                    path,
                    depth,
                    state,
                )
            }

            is Uri -> {
                typed("uri", JsonPrimitive(value.toString()))
            }

            is Parcelable -> {
                JsonObject(
                    sortedMapOf(
                        "marker" to JsonPrimitive("parcelable_omitted"),
                        "runtimeType" to JsonPrimitive(value.javaClass.name),
                        "type" to JsonPrimitive("parcelable"),
                    ),
                )
            }

            else -> {
                if (value.javaClass.isArray) {
                    encodeReflectiveArray(value, path, depth, state)
                } else {
                    encodeUnknown(value, path)
                }
            }
        }
    }

    private fun encodeString(
        value: String,
        path: String,
    ): JsonElement {
        val bytes = value.utf8Bytes()
        if (bytes > limits.maxStringUtf8Bytes) {
            throw LimitBreach("maxStringUtf8Bytes", path, bytes, "string")
        }
        return typed("string", JsonPrimitive(value))
    }

    private fun encodeMap(
        value: Map<*, *>,
        path: String,
        depth: Int,
        state: SerializationState,
    ): JsonElement =
        composite(value, path, "object", state) {
            val keys =
                try {
                    value.keys.toList()
                } catch (failure: Exception) {
                    return@composite failureMarker("key_enumeration_failure", failure)
                }
            val entries =
                keys
                    .map { originalKey ->
                        val keyElement = encodeMapKey(originalKey)
                        val childPath = "$path.${escapePath(keyElement.baseKey)}"
                        val valueElement =
                            try {
                                encode(value[originalKey], childPath, depth + 1, state)
                            } catch (breach: LimitBreach) {
                                throw breach
                            } catch (failure: Exception) {
                                failureMarker("per_key_failure", failure)
                            }
                        EncodedMapEntry(keyElement, valueElement)
                    }.sortedWith(
                        compareBy<EncodedMapEntry> {
                            it.key.baseKey
                        }.thenBy { it.key.json.toString() }.thenBy { it.value.toString() },
                    )
            val objectValues = linkedMapOf<String, JsonElement>()
            entries.groupBy { it.key.baseKey }.toSortedMap().forEach { (key, collisions) ->
                objectValues[key] =
                    if (collisions.size == 1) {
                        collisions.single().value
                    } else {
                        JsonObject(
                            sortedMapOf(
                                "entries" to
                                    JsonArray(
                                        collisions.map { collision ->
                                            JsonObject(
                                                sortedMapOf(
                                                    "key" to collision.key.json,
                                                    "value" to collision.value,
                                                ),
                                            )
                                        },
                                    ),
                                "marker" to JsonPrimitive("key_collision"),
                                "type" to JsonPrimitive("collision"),
                            ),
                        )
                    }
            }
            JsonObject(objectValues)
        }

    private fun encodeBundle(
        bundle: Bundle,
        path: String,
        depth: Int,
        state: SerializationState,
    ): JsonElement =
        composite(bundle, path, "bundle", state) {
            val values = linkedMapOf<String, JsonElement>()
            val keys =
                runCatching { bundle.keySet().toList().sorted() }.getOrElse {
                    return@composite failureMarker("key_enumeration_failure", it)
                }
            keys.forEach { key ->
                val childPath = "$path.${escapePath(key)}"
                values[key] =
                    try {
                        encode(bundle.get(key), childPath, depth + 1, state)
                    } catch (breach: LimitBreach) {
                        throw breach
                    } catch (failure: Throwable) {
                        failureMarker("per_key_failure", failure)
                    }
            }
            JsonObject(values)
        }

    private fun encodeIterable(
        value: Iterable<*>,
        path: String,
        depth: Int,
        state: SerializationState,
        ordered: Boolean,
    ): JsonElement =
        composite(value, path, "array", state) {
            val iterator = value.iterator()
            val elements = mutableListOf<JsonElement>()
            while (iterator.hasNext()) {
                if (elements.size >= limits.maxArrayEntries) {
                    throw LimitBreach("maxArrayEntries", path, elements.size + 1, "array")
                }
                elements += encode(iterator.next(), "$path[${elements.size}]", depth + 1, state)
            }
            JsonArray(if (ordered) elements else elements.sortedBy(JsonElement::toString))
        }

    private fun encodeReflectiveArray(
        value: Any,
        path: String,
        depth: Int,
        state: SerializationState,
    ): JsonElement =
        composite(value, path, "array", state) {
            val length = ReflectArray.getLength(value)
            if (length > limits.maxArrayEntries) {
                throw LimitBreach("maxArrayEntries", path, length, "array")
            }
            JsonArray(
                (0 until length).map { index ->
                    encode(ReflectArray.get(value, index), "$path[$index]", depth + 1, state)
                },
            )
        }

    private fun encodeOpaque(
        value: SafeOpaqueValue,
        path: String,
        depth: Int,
        state: SerializationState,
    ): JsonElement =
        encodeMap(
            mapOf(
                "marker" to value.marker,
                "metadata" to value.metadata,
                "safeType" to value.safeType,
            ),
            path,
            depth,
            state,
        )

    private fun encodeAction(
        value: SafeActionValue,
        path: String,
        depth: Int,
        state: SerializationState,
    ): JsonElement =
        encodeMap(
            mapOf(
                "authenticationRequired" to value.authenticationRequired,
                "contextual" to value.contextual,
                "extras" to value.extras,
                "icon" to value.icon,
                "pendingIntent" to
                    if (value.hasPendingIntent) {
                        SafeOpaqueValue("pendingIntent", "pending_intent_omitted", emptyMap())
                    } else {
                        null
                    },
                "remoteInputs" to value.remoteInputs,
                "semanticAction" to value.semanticAction,
                "showsUserInterface" to value.showsUserInterface,
                "title" to value.title,
            ),
            path,
            depth,
            state,
        )

    private fun encodeMessage(
        value: SafeMessageValue,
        path: String,
        depth: Int,
        state: SerializationState,
    ): JsonElement =
        encodeMap(
            mapOf(
                "sender" to value.sender,
                "dataMimeType" to value.dataMimeType,
                "dataUri" to value.dataUri,
                "extras" to value.extras,
                "text" to value.text,
                "timestampEpochMillis" to value.timestampEpochMillis,
            ),
            path,
            depth,
            state,
        )

    private fun encodeRemoteInput(
        value: SafeRemoteInputValue,
        path: String,
        depth: Int,
        state: SerializationState,
    ): JsonElement =
        encodeMap(
            mapOf(
                "allowFreeFormInput" to value.allowFreeFormInput,
                "allowedDataTypes" to value.allowedDataTypes.sorted(),
                "choices" to value.choices,
                "editChoicesBeforeSending" to value.editChoicesBeforeSending,
                "extras" to value.extras,
                "label" to value.label,
                "resultKey" to value.resultKey,
            ),
            path,
            depth,
            state,
        )

    private fun encodePerson(
        value: SafePersonValue,
        path: String,
        depth: Int,
        state: SerializationState,
    ): JsonElement =
        encodeMap(
            mapOf(
                "icon" to value.icon,
                "important" to value.isImportant,
                "bot" to value.isBot,
                "key" to value.key,
                "name" to value.name,
                "uri" to value.uri,
            ),
            path,
            depth,
            state,
        )

    private fun encodeUnknown(
        value: Any,
        path: String,
    ): JsonElement =
        JsonObject(
            sortedMapOf(
                "marker" to JsonPrimitive("unsupported_value"),
                "path" to JsonPrimitive(path.take(MAX_MARKER_PATH_CHARS)),
                "runtimeType" to JsonPrimitive(value.javaClass.name),
                "type" to JsonPrimitive("unknown"),
            ),
        )

    private fun composite(
        value: Any,
        path: String,
        type: String,
        state: SerializationState,
        block: () -> JsonElement,
    ): JsonElement {
        if (state.active.put(value, path) != null) {
            return JsonObject(
                sortedMapOf(
                    "marker" to JsonPrimitive("cycle"),
                    "type" to JsonPrimitive("cycle"),
                ),
            )
        }
        return try {
            typed(type, block())
        } finally {
            state.active.remove(value)
        }
    }

    private fun typed(
        type: String,
        value: JsonElement,
        vararg extra: Pair<String, JsonElement>,
    ): JsonObject =
        JsonObject(
            sortedMapOf<String, JsonElement>().apply {
                putAll(extra)
                put("type", JsonPrimitive(type))
                put("value", value)
            },
        )

    private fun failureMarker(
        marker: String,
        failure: Throwable,
    ): JsonObject =
        JsonObject(
            sortedMapOf(
                "failureType" to JsonPrimitive(failure.javaClass.name),
                "marker" to JsonPrimitive(marker),
                "type" to JsonPrimitive("error"),
            ),
        )

    private fun encodeMapKey(value: Any?): EncodedMapKey =
        try {
            when (value) {
                null -> {
                    EncodedMapKey("null", typed("null", JsonNull))
                }

                is String -> {
                    EncodedMapKey(value, encodeString(value, "\$key"))
                }

                is Boolean -> {
                    EncodedMapKey(value.toString(), typed("boolean", JsonPrimitive(value)))
                }

                is Byte, is Short, is Int, is Long -> {
                    val number = (value as Number).toLong()
                    EncodedMapKey(number.toString(), typed("integer", JsonPrimitive(number)))
                }

                is Float, is Double -> {
                    val number = (value as Number).toDouble()
                    EncodedMapKey(number.toString(), typed("number", JsonPrimitive(number)))
                }

                is Char -> {
                    EncodedMapKey(value.toString(), encodeString(value.toString(), "\$key"))
                }

                is Enum<*> -> {
                    EncodedMapKey(value.name, typed("enum", JsonPrimitive(value.name)))
                }

                else -> {
                    val runtimeType = value.javaClass.name
                    EncodedMapKey(
                        runtimeType,
                        JsonObject(
                            sortedMapOf(
                                "marker" to JsonPrimitive("unsupported_map_key"),
                                "runtimeType" to JsonPrimitive(runtimeType),
                                "type" to JsonPrimitive("map_key"),
                            ),
                        ),
                    )
                }
            }
        } catch (failure: Exception) {
            val runtimeType = value?.javaClass?.name ?: "null"
            EncodedMapKey(
                runtimeType,
                JsonObject(
                    sortedMapOf(
                        "failureType" to JsonPrimitive(failure.javaClass.name),
                        "marker" to JsonPrimitive("key_conversion_failure"),
                        "runtimeType" to JsonPrimitive(runtimeType),
                        "type" to JsonPrimitive("map_key"),
                    ),
                ),
            )
        }

    private fun minimalLimitEnvelope(
        identity: EnvelopeIdentity,
        breach: LimitBreach,
    ): JsonObject =
        JsonObject(
            sortedMapOf(
                "eventId" to JsonPrimitive(identity.eventId.take(MAX_IDENTITY_CHARS)),
                "identity" to
                    JsonObject(
                        sortedMapOf(
                            "notificationId" to JsonPrimitive(identity.notificationId),
                            "notificationKey" to JsonPrimitive(identity.notificationKey.take(MAX_IDENTITY_CHARS)),
                            "packageName" to JsonPrimitive(identity.packageName.take(MAX_IDENTITY_CHARS)),
                            "postTimeEpochMillis" to JsonPrimitive(identity.postTimeEpochMillis),
                        ),
                    ),
                "limitEnvelope" to JsonPrimitive(true),
                "limitName" to JsonPrimitive(breach.limitName),
                "measuredValue" to JsonPrimitive(breach.measuredValue),
                "originalSafeType" to JsonPrimitive(breach.originalSafeType.take(512)),
                "path" to JsonPrimitive(breach.path.take(MAX_MARKER_PATH_CHARS)),
            ),
        )

    private fun minimalFailureEnvelope(
        identity: EnvelopeIdentity,
        failureType: String,
    ): JsonObject =
        JsonObject(
            sortedMapOf(
                "eventId" to JsonPrimitive(identity.eventId.take(MAX_IDENTITY_CHARS)),
                "failureType" to JsonPrimitive(failureType.take(512)),
                "identity" to
                    JsonObject(
                        sortedMapOf(
                            "notificationId" to JsonPrimitive(identity.notificationId),
                            "notificationKey" to JsonPrimitive(identity.notificationKey.take(MAX_IDENTITY_CHARS)),
                            "packageName" to JsonPrimitive(identity.packageName.take(MAX_IDENTITY_CHARS)),
                            "postTimeEpochMillis" to JsonPrimitive(identity.postTimeEpochMillis),
                        ),
                    ),
                "marker" to JsonPrimitive("serialization_failure"),
                "quarantined" to JsonPrimitive(true),
            ),
        )

    @TargetApi(Build.VERSION_CODES.P)
    private fun Person.toSafePerson(): SafePersonValue =
        SafePersonValue(
            name = name?.toString(),
            uri = uri,
            key = key,
            isBot = isBot,
            isImportant = isImportant,
            icon = icon,
        )

    private fun safeType(value: Any?): String =
        when (value) {
            null -> "null"
            is String, is CharSequence -> "string"
            is Map<*, *>, is Bundle -> "object"
            is Iterable<*> -> "array"
            else -> value.javaClass.name
        }

    private fun escapePath(key: String): String = key.replace("\\", "\\\\").replace(".", "\\.")

    private fun String.utf8Bytes(): Int = toByteArray(StandardCharsets.UTF_8).size

    private class SerializationState {
        var nodes: Int = 0
        val active = IdentityHashMap<Any, String>()
    }

    private data class LimitBreach(
        val limitName: String,
        val path: String,
        val measuredValue: Int,
        val originalSafeType: String,
    ) : RuntimeException()

    private data class EncodedMapKey(
        val baseKey: String,
        val json: JsonElement,
    )

    private data class EncodedMapEntry(
        val key: EncodedMapKey,
        val value: JsonElement,
    )

    private companion object {
        const val MAX_IDENTITY_CHARS = 2_048
        const val MAX_MARKER_PATH_CHARS = 2_048
    }
}
